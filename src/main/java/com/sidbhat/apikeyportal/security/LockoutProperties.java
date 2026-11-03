package com.sidbhat.apikeyportal.security;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How hard the portal makes it to guess a password - see {@link LoginAttemptService}.
 * <p>
 * Deliberately short. A lockout long enough to be a nuisance to an attacker is
 * also long enough to be a way of locking a colleague out on purpose, since
 * anyone can type anyone else's email at the sign-in page - which is also why an
 * account only locks once the failures have come from {@link #getMinAddresses()}
 * different addresses. One address misbehaving locks that address, not its target.
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

    /**
     * How many different addresses an account's failures must have come from
     * before the account itself locks. Below this, only the per-address lock
     * engages - so one caller typing wrong passwords at somebody's email locks
     * themselves out, not their target, and the super admin stays reachable as
     * the emergency account. A real distributed guessing run uses an address per
     * guess and sails past this. 1 restores the old behaviour.
     */
    @Min(1)
    private final int minAddresses;

    public LockoutProperties(int maxAttempts, Duration lockoutFor, @DefaultValue("3") int minAddresses) {
        this.maxAttempts = maxAttempts;
        this.lockoutFor = lockoutFor;
        this.minAddresses = minAddresses;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Duration getLockoutFor() {
        return lockoutFor;
    }

    public int getMinAddresses() {
        return minAddresses;
    }
}
