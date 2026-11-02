package com.sidbhat.apikeyportal.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.sidbhat.apikeyportal.logging.AuditLog;
import com.sidbhat.apikeyportal.user.UserAccount;

/**
 * Counts failed password sign-ins and refuses more of them for a while once
 * there have been too many.
 * <p>
 * Failures are counted twice over, and either count is enough to lock:
 * <ul>
 *   <li><b>Per account</b>, so one password cannot be guessed from a thousand
 *       addresses - a botnet with an address each would otherwise never trip a
 *       per-address limit.</li>
 *   <li><b>Per address</b>, so one caller cannot work down a list of accounts a
 *       few guesses at a time, staying under the per-account limit for each.</li>
 * </ul>
 * Counters live in memory, not the database: they are worth nothing after the
 * window has passed, writing a row per failed guess is exactly what someone
 * flooding the sign-in page would want, and a lock that forgets itself on
 * restart is the right failure mode for a portal with one instance. On more than
 * one instance each gets its own counters, so the effective limit is the limit
 * times the number of instances - still a limit, and still far short of the
 * unlimited guessing there was before.
 */
@Service
public class LoginAttemptService {

    /** Beyond this many tracked accounts and addresses, expired entries are swept out. */
    private static final int SWEEP_THRESHOLD = 1_000;

    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    private final LockoutProperties properties;
    private final AuditLog audit;
    private final Clock clock;

    @Autowired
    public LoginAttemptService(LockoutProperties properties, AuditLog audit) {
        this(properties, audit, Clock.systemUTC());
    }

    /** Lets a test move time on rather than wait out a real lockout. */
    LoginAttemptService(LockoutProperties properties, AuditLog audit, Clock clock) {
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    /** True if sign-in should be refused for this account or this address. */
    public boolean isLockedOut(String email, String address) {
        return lockedUntil(email, address) != null;
    }

    /**
     * When the lock on this account or address lifts, or null if neither is locked.
     * The later of the two, when both are.
     */
    public Instant lockedUntil(String email, String address) {
        Instant now = clock.instant();
        Instant account = lockedUntil(accountKey(email), now);
        Instant caller = lockedUntil(addressKey(address), now);
        if (account == null) {
            return caller;
        }
        return caller == null || account.isAfter(caller) ? account : caller;
    }

    /**
     * Counts one failed sign-in. Either counter reaching the limit locks that
     * account, or that address, for the configured time.
     */
    public void recordFailure(String email, String address) {
        Instant now = clock.instant();
        if (failures.size() > SWEEP_THRESHOLD) {
            sweep(now);
        }
        if (count(accountKey(email), now)) {
            audit.refused("account_locked_out", UserAccount.normalizeEmail(email),
                    UserAccount.normalizeEmail(email),
                    properties.getMaxAttempts() + " failed sign-ins; locked until "
                            + lockedUntil(accountKey(email), now));
        }
        if (count(addressKey(address), now)) {
            audit.refused("address_locked_out", null, address,
                    properties.getMaxAttempts() + " failed sign-ins; locked until "
                            + lockedUntil(addressKey(address), now));
        }
    }

    /**
     * Forgets the failures behind a sign-in that worked. Someone who got there in
     * the end should not be a few typos away from locking themselves out, and the
     * address they came from is evidently not working through a list of accounts.
     */
    public void recordSuccess(String email, String address) {
        remove(accountKey(email));
        remove(addressKey(address));
    }

    /**
     * Drops every counter. For tests, which share one application context and
     * would otherwise hand each other half-used attempts.
     */
    public void forgetAll() {
        failures.clear();
    }

    /** Counts a failure against one key, returning true if that key has just locked. */
    private boolean count(String key, Instant now) {
        if (key == null) {
            return false;
        }
        AtomicBoolean justLocked = new AtomicBoolean();
        failures.compute(key, (ignored, existing) -> {
            if (existing != null && existing.isLocked(now)) {
                // already locked; nothing to count until it lifts
                return existing;
            }
            int attempts = existing == null || existing.hasExpired(now, window()) ? 1 : existing.attempts() + 1;
            if (attempts >= properties.getMaxAttempts()) {
                justLocked.set(true);
                // the count resets as the lock goes on, so the attempt after it
                // lifts is the first of a fresh run rather than an instant re-lock
                return new Failures(0, now, now.plus(properties.getLockoutFor()));
            }
            return new Failures(attempts, now, null);
        });
        return justLocked.get();
    }

    private Instant lockedUntil(String key, Instant now) {
        if (key == null) {
            return null;
        }
        Failures recorded = failures.get(key);
        return recorded != null && recorded.isLocked(now) ? recorded.lockedUntil() : null;
    }

    private void remove(String key) {
        if (key != null) {
            failures.remove(key);
        }
    }

    private void sweep(Instant now) {
        failures.values().removeIf(recorded -> recorded.hasExpired(now, window()));
    }

    /** How far back failures are counted. The same span as the lock itself. */
    private Duration window() {
        return properties.getLockoutFor();
    }

    /** Null for a blank email, so an empty form field is only counted against the address. */
    private static String accountKey(String email) {
        String normalized = UserAccount.normalizeEmail(email);
        return normalized == null || normalized.isBlank() ? null : "account:" + normalized;
    }

    private static String addressKey(String address) {
        return address == null || address.isBlank() ? null : "address:" + address;
    }

    /**
     * The failures recorded against one account or address.
     *
     * @param attempts      failures so far in this run, reset to zero by a lock
     * @param lastFailureAt when the run was last added to, for expiry
     * @param lockedUntil   when the lock lifts, or null while there is no lock
     */
    private record Failures(int attempts, Instant lastFailureAt, Instant lockedUntil) {

        boolean isLocked(Instant now) {
            return lockedUntil != null && now.isBefore(lockedUntil);
        }

        /** True once this run is old enough that the next failure starts a new one. */
        boolean hasExpired(Instant now, Duration window) {
            return lockedUntil != null ? !now.isBefore(lockedUntil) : lastFailureAt.plus(window).isBefore(now);
        }
    }
}
