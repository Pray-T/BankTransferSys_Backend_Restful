package com.banktransfer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.idempotency")
public class IdempotencyProperties {

    /** 기본 24시간. PENDING 키 만료로 인한 이중 이체/COMPLETED 미기록을 방지합니다. */
    private int ttlSeconds = 86400;
}
