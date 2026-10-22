package com.banktransfer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.idempotency")
public class IdempotencyProperties {

    /** Redis 캐시 TTL(초). 재조회 원본은 MySQL이며 이 값으로 SUCCESS 행을 지우지 않는다. */
    private int ttlSeconds = 86400;
}
