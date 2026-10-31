package com.sidbhat.apikeyportal.notification;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How and when expiry reminders go out.
 */
@ConfigurationProperties(prefix = "portal.notifications")
@Validated
public class NotificationProperties {

    /** Off, and the daily sweep never runs. */
    private final boolean enabled;

    /** Spring cron expression for the daily sweep. Once a day is plenty: the thresholds are days apart. */
    @NotBlank
    private final String cron;

    /** From address on reminder emails. */
    @NotBlank
    private final String fromAddress;

    /**
     * Where the links in the email point. There is no request to take it from
     * when a scheduled job sends, so this has to be configured.
     */
    @NotBlank
    private final String baseUrl;

    public NotificationProperties(boolean enabled, String cron, String fromAddress, String baseUrl) {
        this.enabled = enabled;
        this.cron = cron;
        this.fromAddress = fromAddress;
        this.baseUrl = baseUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getCron() {
        return cron;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
