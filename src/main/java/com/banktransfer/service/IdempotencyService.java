package com.banktransfer.service;

import java.time.Duration;
import java.util.Objects;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.banktransfer.config.IdempotencyProperties;
import com.banktransfer.model.IdempotencyRecord;
import com.banktransfer.model.IdempotencyStatus;

/**
 * 커밋된 이체의 Redis 캐시.
 * 재조회와 중복 판정은 MySQL {@code transfer_idempotency}가 담당하며, 이 서비스는 요청 경로에서 읽지 않는다.
 */
@Service
public class IdempotencyService {
    private static final String KEY_PREFIX = "idempo:";
    private static final String RESOURCE_TYPE = "TRANSFER";

    private final RedisTemplate<String, IdempotencyRecord> redisTemplate;
    private final IdempotencyProperties properties;

    public IdempotencyService(RedisTemplate<String, IdempotencyRecord> redisTemplate,
                              IdempotencyProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 커밋 이후 캐시 적재. TTL이 지나 키가 사라져도 MySQL 행은 유지된다.
     * 호출부(afterCommit)가 예외를 삼킨다.
     */
    public void cacheSuccess(String idempotencyKey, String requestHash, Long transferId) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setKey(idempotencyKey);
        record.setResourceType(RESOURCE_TYPE);
        record.setRequestHash(requestHash);
        record.setResourceId(transferId);
        record.setStatus(IdempotencyStatus.COMPLETED);
        record.setCreatedAtEpochMillis(System.currentTimeMillis());

        String redisKey = Objects.requireNonNull(buildRedisKey(idempotencyKey));
        redisTemplate.opsForValue().set(redisKey, record, ttl());
    }

    private Duration ttl() {
        return Duration.ofSeconds(Math.max(1, properties.getTtlSeconds()));
    }

    private String buildRedisKey(String idempotencyKey) {
        return KEY_PREFIX + RESOURCE_TYPE + ":" + idempotencyKey;
    }
}
