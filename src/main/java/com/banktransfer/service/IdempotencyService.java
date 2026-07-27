package com.banktransfer.service;

import java.time.Duration;
import java.util.Objects;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import com.banktransfer.config.IdempotencyProperties;
import com.banktransfer.exception.ConflictException;
import com.banktransfer.model.IdempotencyRecord;
import com.banktransfer.model.IdempotencyStatus;

@Service
public class IdempotencyService {
    private static final String KEY_PREFIX = "idempo:";
    private static final Duration RECLAIM_LOCK_TTL = Duration.ofSeconds(5);

    private final RedisTemplate<String, IdempotencyRecord> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final IdempotencyProperties properties;

    public IdempotencyService(RedisTemplate<String, IdempotencyRecord> redisTemplate,
                              StringRedisTemplate stringRedisTemplate,
                              IdempotencyProperties properties) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    /**
     * 멱등성 키를 확보하거나 기존 레코드를 반환한다.
     * FAILED 상태는 원자적으로 PENDING으로 재획득을 시도하며, 성공 시 newlyCreated=true.
     */
    public IdempotencyStartResult beginOrGetExisting(String key, String resourceType, String requestHash) {
        String redisKey = Objects.requireNonNull(buildRedisKey(resourceType, key));
        ValueOperations<String, IdempotencyRecord> ops = redisTemplate.opsForValue();
        Duration ttl = ttl();

        IdempotencyRecord existing = ops.get(redisKey);
        if (existing != null) {
            return handleExisting(existing, key, resourceType, requestHash, redisKey, ttl);
        }

        IdempotencyRecord fresh = newPendingRecord(key, resourceType, requestHash);
        Boolean created = ops.setIfAbsent(redisKey, fresh, ttl);
        if (Boolean.FALSE.equals(created)) {
            IdempotencyRecord found = ops.get(redisKey);
            if (found == null) {
                Boolean retried = ops.setIfAbsent(redisKey, fresh, ttl);
                if (Boolean.TRUE.equals(retried)) {
                    return new IdempotencyStartResult(fresh, true);
                }
                found = ops.get(redisKey);
                if (found == null) {
                    throw new ConflictException("멱등성 키 확보에 실패했습니다. 잠시 후 다시 시도해주세요.");
                }
            }
            return handleExisting(found, key, resourceType, requestHash, redisKey, ttl);
        }
        return new IdempotencyStartResult(fresh, true);
    }

    /**
     * FAILED → PENDING 원자적 재획득.
     * reclaim 락으로 동시 재시도를 직렬화해 이중 이체를 방지한다.
     */
    public boolean tryReclaimFailed(IdempotencyRecord record) {
        String redisKey = Objects.requireNonNull(buildRedisKey(record.getResourceType(), record.getKey()));
        String reclaimKey = redisKey + ":reclaim";
        Duration ttl = ttl();

        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(reclaimKey, "1", RECLAIM_LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            return false;
        }
        try {
            IdempotencyRecord current = redisTemplate.opsForValue().get(redisKey);
            if (current == null) {
                record.setStatus(IdempotencyStatus.PENDING);
                record.setResourceId(null);
                record.setCreatedAtEpochMillis(System.currentTimeMillis());
                Boolean created = redisTemplate.opsForValue().setIfAbsent(redisKey, record, ttl);
                return Boolean.TRUE.equals(created);
            }
            if (current.getStatus() != IdempotencyStatus.FAILED) {
                return false;
            }
            if (!current.getRequestHash().equals(record.getRequestHash())) {
                throw new ConflictException("Idempotency-Key 재사용이 다른 요청 페이로드와 충돌합니다.");
            }
            record.setStatus(IdempotencyStatus.PENDING);
            record.setResourceId(null);
            record.setCreatedAtEpochMillis(System.currentTimeMillis());
            redisTemplate.opsForValue().set(redisKey, record, ttl);
            return true;
        } finally {
            stringRedisTemplate.delete(reclaimKey);
        }
    }

    public void finalizeSuccess(IdempotencyRecord record, Long resourceId) {
        record.setStatus(IdempotencyStatus.COMPLETED);
        record.setResourceId(resourceId);
        persistWithRemainingTtl(record);
    }

    public void finalizeFailure(IdempotencyRecord record) {
        record.setStatus(IdempotencyStatus.FAILED);
        persistWithRemainingTtl(record);
    }

    private IdempotencyStartResult handleExisting(IdempotencyRecord existing,
                                                  String key,
                                                  String resourceType,
                                                  String requestHash,
                                                  String redisKey,
                                                  Duration ttl) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ConflictException("Idempotency-Key 재사용이 다른 요청 페이로드와 충돌합니다.");
        }
        if (existing.getStatus() == IdempotencyStatus.FAILED) {
            IdempotencyRecord candidate = newPendingRecord(key, resourceType, requestHash);
            if (tryReclaimFailed(candidate)) {
                return new IdempotencyStartResult(candidate, true);
            }
            IdempotencyRecord latest = redisTemplate.opsForValue().get(redisKey);
            if (latest == null) {
                Boolean created = redisTemplate.opsForValue().setIfAbsent(redisKey, candidate, ttl);
                if (Boolean.TRUE.equals(created)) {
                    return new IdempotencyStartResult(candidate, true);
                }
                latest = redisTemplate.opsForValue().get(redisKey);
            }
            if (latest != null) {
                if (!latest.getRequestHash().equals(requestHash)) {
                    throw new ConflictException("Idempotency-Key 재사용이 다른 요청 페이로드와 충돌합니다.");
                }
                return new IdempotencyStartResult(latest, false);
            }
            return new IdempotencyStartResult(existing, false);
        }
        return new IdempotencyStartResult(existing, false);
    }

    private IdempotencyRecord newPendingRecord(String key, String resourceType, String requestHash) {
        IdempotencyRecord fresh = new IdempotencyRecord();
        fresh.setKey(key);
        fresh.setResourceType(resourceType);
        fresh.setRequestHash(requestHash);
        fresh.setStatus(IdempotencyStatus.PENDING);
        fresh.setCreatedAtEpochMillis(System.currentTimeMillis());
        return fresh;
    }

    private void persistWithRemainingTtl(IdempotencyRecord record) {
        long nowMs = System.currentTimeMillis();
        long elapsedSec = Math.max(0L, (nowMs - Math.max(0L, record.getCreatedAtEpochMillis())) / 1000L);
        long remain = Math.max(0L, properties.getTtlSeconds() - elapsedSec);
        if (remain > 0) {
            String redisKey = Objects.requireNonNull(buildRedisKey(record.getResourceType(), record.getKey()));
            redisTemplate.opsForValue()
                    .set(redisKey, record, Duration.ofSeconds(remain));
        }
    }

    private Duration ttl() {
        return Duration.ofSeconds(Math.max(1, properties.getTtlSeconds()));
    }

    private String buildRedisKey(String resourceType, String key) {
        return KEY_PREFIX + resourceType + ":" + key;
    }
}

