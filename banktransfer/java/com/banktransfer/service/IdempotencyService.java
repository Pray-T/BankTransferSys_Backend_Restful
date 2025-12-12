package com.banktransfer.service;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import com.banktransfer.exception.ConflictException;
import com.banktransfer.config.IdempotencyProperties;
import com.banktransfer.model.IdempotencyRecord;
import com.banktransfer.model.IdempotencyStatus;

@Service
public class IdempotencyService {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String KEY_PREFIX = "idempo:";

    private final RedisTemplate<String, IdempotencyRecord> redisTemplate;
    private final IdempotencyProperties properties;

    public IdempotencyService(RedisTemplate<String, IdempotencyRecord> redisTemplate,
                              IdempotencyProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public IdempotencyRecord beginOrGetExisting(String key, String resourceType, String requestHash) {
        String redisKey = buildRedisKey(resourceType, key);
        ValueOperations<String, IdempotencyRecord> ops = redisTemplate.opsForValue();
        Duration ttl = Duration.ofSeconds(Math.max(1, properties.getTtlSeconds()));

        IdempotencyRecord existing = ops.get(redisKey);
        if (existing != null) {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new ConflictException("Idempotency-Key 재사용이 다른 요청 페이로드와 충돌합니다.");
            }
            return existing;
        }

        IdempotencyRecord fresh = new IdempotencyRecord();
        fresh.setKey(key);
        fresh.setResourceType(resourceType);
        fresh.setRequestHash(requestHash);
        fresh.setStatus(IdempotencyStatus.PENDING);
        fresh.setCreatedAtEpochMillis(System.currentTimeMillis());

        Boolean created = ops.setIfAbsent(redisKey, fresh, ttl);
        if (Boolean.FALSE.equals(created)) {
            IdempotencyRecord found = ops.get(redisKey);
            if (found == null) {
                // Rare race; try once more
                ops.setIfAbsent(redisKey, fresh, ttl);
                return fresh;
            }
            if (!found.getRequestHash().equals(requestHash)) {
                throw new ConflictException("Idempotency-Key 재사용이 다른 요청 페이로드와 충돌합니다.");
            }
            return found;
        }
        return fresh;
    }

    public void finalizeSuccess(IdempotencyRecord record, Long resourceId) {
        record.setStatus(IdempotencyStatus.COMPLETED);
        record.setResourceId(resourceId);
        long nowMs = System.currentTimeMillis();
        long elapsedSec = Math.max(0L, (nowMs - Math.max(0L, record.getCreatedAtEpochMillis())) / 1000L);
        long remain = Math.max(0L, properties.getTtlSeconds() - elapsedSec);
        if (remain > 0) {
            redisTemplate.opsForValue()
                    .set(buildRedisKey(record.getResourceType(), record.getKey()),
                            record, Duration.ofSeconds(remain));
        }
    }

    public void finalizeFailure(IdempotencyRecord record) {
        record.setStatus(IdempotencyStatus.FAILED);
        long nowMs = System.currentTimeMillis();
        long elapsedSec = Math.max(0L, (nowMs - Math.max(0L, record.getCreatedAtEpochMillis())) / 1000L);
        long remain = Math.max(0L, properties.getTtlSeconds() - elapsedSec);
        if (remain > 0) {
            redisTemplate.opsForValue()
                    .set(buildRedisKey(record.getResourceType(), record.getKey()),
                            record, Duration.ofSeconds(remain));
        }
    }

    private String buildRedisKey(String resourceType, String key) {
        return KEY_PREFIX + resourceType + ":" + key;
    }
}

