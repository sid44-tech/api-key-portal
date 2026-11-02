package com.sidbhat.apikeyportal.security;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How hard the portal makes it to guess a password - see {@link LoginAttemptService}.
 * <p>
 * Deliberately short. A lockout long enough to be a nuisance to an attacker is
 * also long enough to be a way of locking a colleague out on purpose, since
 * anyone can type anyone else's email at the sign-in page.
 */
@ConfigurationProperties(prefix = "portal.security.lockout")
@Validated
public class LockoutProperties {

    /** Failures, per account and per address, before sign-in is refused. */
    @Min(1)
    private final int maxAttempts;

    /**
     * How long sign-in stays refused once the limit is hit. Doubles as the window
     * failures are counted over: a run of failures older than this is forgotten.
     */
    @NotNull
    private final Duration lockoutFor;

    public LockoutProperties(int maxAttempts, Duration lockoutFor) {
        this.maxAttempts = maxAttempts;
        this.lockoutFor = lockoutFor;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Duration getLockoutFor() {
        return lockoutFor;
    }
}
