package io.github.apikeyportal.rotation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * When and whether the portal mints replacements for expiring keys. Whose keys
 * it may do that for is not configuration - that is each owner's own setting.
 */
@ConfigurationProperties(prefix = "portal.rotation")
@Validated
public class RotationProperties {

    /** Off, and the daily rotation sweep never runs. */
    private final boolean enabled;

    /**
     * Spring cron for the sweep. Defaults to an hour before the expiry reminder
     * sweep, so that by the time reminders are considered, a key that was going
     * to be replaced already has been - and its reminder is not sent.
     */
    @NotBlank
    private final String cron;

    /**
     * How many days before expiry the replacement is minted. The point of the
     * head start: both secrets work during the overlap, so the consumer switches
     * at leisure and nothing stops working at midnight.
     */
    @Min(1)
    @Max(30)
    private final int leadDays;

    public RotationProperties(boolean enabled, String cron, int leadDays) {
        this.enabled = enabled;
        this.cron = cron;
        this.leadDays = leadDays;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getCron() {
        return cron;
    }

    public int getLeadDays() {
        return leadDays;
    }
}
