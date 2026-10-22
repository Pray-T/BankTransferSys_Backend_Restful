package com.banktransfer.service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.banktransfer.config.TransferThrottleProperties;
import com.banktransfer.exception.TransferCooldownException;

/**
 * 같은 방향(출금 계좌 → 입금 계좌)의 새 이체에 대한 간격 제한.
 * 존재 확인과 기록은 {@code SET key value NX EX} 한 번의 왕복으로 한다.
 */
@Service
public class TransferThrottleService {
    private static final Logger log = LoggerFactory.getLogger(TransferThrottleService.class);
    private static final String KEY_PREFIX = "trans:cooldown:";

    /**
     * 값이 이 요청의 토큰과 같을 때만 삭제한다. 다른 요청이 다시 잡은 키는 지우지 않는다.
     */
    private static final DefaultRedisScript<Long> RELEASE_IF_OWNED = new DefaultRedisScript<>(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """,
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final TransferThrottleProperties properties;

    public TransferThrottleService(StringRedisTemplate redisTemplate,
                                   TransferThrottleProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 쿨다운 키를 원자적으로 잡는다. 이미 있으면 {@link TransferCooldownException}(429).
     * Redis 연결 오류는 삼키지 않는다. 이체가 커밋된 뒤 키를 지우는 일은 없다.
     *
     * @return 이 요청의 토큰. 쿨다운이 꺼져 있으면 null이며 키를 만들지 않는다.
     */
    public String acquire(String sourceAccountNumber, String targetAccountNumber) {
        if (!isEnabled()) {
            return null;
        }
        String key = Objects.requireNonNull(buildKey(sourceAccountNumber, targetAccountNumber));
        String token = UUID.randomUUID().toString();
        Duration ttl = Objects.requireNonNull(Duration.ofSeconds(properties.getCooldownSeconds()));
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new TransferCooldownException(buildMessage());
        }
        return token;
    }

    /**
     * 롤백된 이체가 자기가 넣은 토큰일 때만 키를 지운다.
     * 해제에 실패하면 ERROR로 남기고 TTL에 맡긴다. 실패를 무시하고 이체를 진행하지는 않는다.
     */
    public void releaseIfOwned(String sourceAccountNumber, String targetAccountNumber, String token) {
        if (token == null) {
            return;
        }
        String key = Objects.requireNonNull(buildKey(sourceAccountNumber, targetAccountNumber));
        try {
            Long removed = redisTemplate.execute(RELEASE_IF_OWNED, List.of(key), token);
            if (removed != null && removed > 0) {
                log.info("롤백된 이체의 쿨다운 키를 해제했습니다. source={}, target={}",
                        sourceAccountNumber, targetAccountNumber);
            } else {
                log.debug("쿨다운 키는 이 요청의 토큰이 아니라 유지합니다. source={}, target={}",
                        sourceAccountNumber, targetAccountNumber);
            }
        } catch (RuntimeException ex) {
            log.error("롤백된 이체의 쿨다운 키를 해제하지 못했습니다. source={}, target={}",
                    sourceAccountNumber, targetAccountNumber, ex);
        }
    }

    private String buildKey(String sourceAccountNumber, String targetAccountNumber) {
        return KEY_PREFIX + normalize(sourceAccountNumber) + "->" + normalize(targetAccountNumber);
    }

    private static String normalize(String accountNumber) {
        return accountNumber.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isEnabled() {
        return properties.getCooldownSeconds() > 0;
    }

    private String buildMessage() {
        return String.format("방금 전 이체가 실행된 계좌 입니다, 추가 이체를 원하신다면 %d초 후 다시 시도해주세요.",
                properties.getCooldownSeconds());
    }
}
