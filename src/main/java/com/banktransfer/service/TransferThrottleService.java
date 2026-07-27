package com.banktransfer.service;

import java.time.Duration;
import java.util.Objects;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.banktransfer.config.TransferThrottleProperties;
import com.banktransfer.exception.TransferCooldownException;

@Service
public class TransferThrottleService {
    private static final String KEY_PREFIX = "trans:cooldown:";

    private final StringRedisTemplate redisTemplate;
    private final TransferThrottleProperties properties;

    public TransferThrottleService(StringRedisTemplate redisTemplate,
                                   TransferThrottleProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void enforceCooldown(String sourceAccountNumber, String targetAccountNumber) {
        if (!isEnabled()) {
            return;
        }
        String key = Objects.requireNonNull(buildKey(sourceAccountNumber, targetAccountNumber));
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            throw new TransferCooldownException(buildMessage());
        }
    }

    public void markSuccess(String sourceAccountNumber, String targetAccountNumber) {
        if (!isEnabled()) {
            return;
        }
        String key = Objects.requireNonNull(buildKey(sourceAccountNumber, targetAccountNumber));
        Duration ttl = Objects.requireNonNull(Duration.ofSeconds(properties.getCooldownSeconds()));
        redisTemplate.opsForValue().set(key, "1", ttl);
    }

    private String buildKey(String sourceAccountNumber, String targetAccountNumber) {
        return KEY_PREFIX + sourceAccountNumber + "->" + targetAccountNumber;
    }

    private boolean isEnabled() {
        return properties.getCooldownSeconds() > 0;
    }

    private String buildMessage() {
        return String.format("방금 전 이체가 실행된 계좌 입니다, 추가 이체를 원하신다면 %d초 후 다시 시도해주세요.",
                properties.getCooldownSeconds());
    }
}


