package com.banktransfer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.transfer")
public class TransferThrottleProperties {

    /**
     * Cooldown duration in seconds for identical source-target transfers.
     */
    private int cooldownSeconds = 10;
}
