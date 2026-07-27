package com.banktransfer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.transfer")
public class TransferThrottleProperties {

    /**
     * Cooldown duration in seconds for identical source-target transfers.
     */
    private int cooldownSeconds = 10;

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(int cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }
}


