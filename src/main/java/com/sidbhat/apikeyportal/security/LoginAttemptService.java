package com.sidbhat.apikeyportal.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.sidbhat.apikeyportal.logging.AuditLog;
import com.sidbhat.apikeyportal.alerts.AlertNotifier;
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
 * The account lock has one extra condition: the failures must have come from
 * several <em>different</em> addresses. Anyone can type anyone's email at the
 * sign-in page, so without it, five wrong passwords were all it took to lock a
 * colleague out on purpose - including the super admin, which is supposed to be
 * the emergency account when SSO is broken. One address misbehaving now locks
 * that address alone (its own counter sees every one of those failures), while
 * the distributed guessing the account lock exists for - an address per guess,
 * precisely to stay under the per-address limit - still trips it.
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

    /**
     * Stands in for a caller whose address is unknown. Counted as spread enough
     * on its own: if addresses cannot be told apart, the per-address lock is
     * blind, and the per-account lock has to fail towards stopping a guessing
     * run rather than towards availability.
     */
    private static final String UNKNOWN_ADDRESS = "unknown";

    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    private final LockoutProperties properties;
    private final AuditLog audit;
    private final Clock clock;

    /** Tells the groups' chat channels about a lock; null in unit tests, which have no channels. */
    private final AlertNotifier alerts;

    @Autowired
    public LoginAttemptService(LockoutProperties properties, AuditLog audit, AlertNotifier alerts) {
        this(properties, audit, Clock.systemUTC(), alerts);
    }

    /** Lets a test move time on rather than wait out a real lockout. */
    LoginAttemptService(LockoutProperties properties, AuditLog audit, Clock clock) {
        this(properties, audit, clock, null);
    }

    LoginAttemptService(LockoutProperties properties, AuditLog audit, Clock clock, AlertNotifier alerts) {
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
        this.alerts = alerts;
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
     * Counts one failed sign-in. The address reaching the limit locks that
     * address; the account reaching it locks the account only once its failures
     * have come from enough different addresses.
     */
    public void recordFailure(String email, String address) {
        Instant now = clock.instant();
        if (failures.size() > SWEEP_THRESHOLD) {
            sweep(now);
        }
        if (count(accountKey(email), callerOf(address), now)) {
            Instant until = lockedUntil(accountKey(email), now);
            audit.refused("account_locked_out", UserAccount.normalizeEmail(email),
                    UserAccount.normalizeEmail(email),
                    properties.getMaxAttempts() + " failed sign-ins from at least "
                            + properties.getMinAddresses() + " addresses; locked until " + until);
            if (alerts != null) {
                alerts.accountLockedOut(UserAccount.normalizeEmail(email), until);
            }
        }
        if (count(addressKey(address), null, now)) {
            Instant until = lockedUntil(addressKey(address), now);
            audit.refused("address_locked_out", null, address,
                    properties.getMaxAttempts() + " failed sign-ins; locked until " + until);
            if (alerts != null) {
                alerts.addressLockedOut(address, until);
            }
        }
    }

    /**
     * Forgets the failures against the account behind a sign-in that worked:
     * someone who got there in the end should not be a few typos away from
     * locking themselves out.
     * <p>
     * The address's count is deliberately left alone. A success proves the caller
     * knows <em>one</em> password, not that they are harmless - an attacker with
     * any valid account could otherwise sign into it between guesses at other
     * accounts and reset the only counter that limits them, since one address
     * can never lock an account by itself. The address count expires on its own
     * once the window passes without further failures.
     */
    public void recordSuccess(String email, String address) {
        remove(accountKey(email));
    }

    /**
     * Drops every counter. For tests, which share one application context and
     * would otherwise hand each other half-used attempts.
     */
    public void forgetAll() {
        failures.clear();
    }

    /**
     * Counts a failure against one key, returning true if that key has just locked.
     *
     * @param caller who the failure came from, for the distinct-address condition
     *               on account keys; null for the address keys themselves, whose
     *               failures are all from one caller by definition
     */
    private boolean count(String key, String caller, Instant now) {
        if (key == null) {
            return false;
        }
        AtomicBoolean justLocked = new AtomicBoolean();
        failures.compute(key, (ignored, existing) -> {
            if (existing != null && existing.isLocked(now)) {
                // already locked; nothing to count until it lifts
                return existing;
            }
            boolean freshRun = existing == null || existing.hasExpired(now, window());
            int attempts = freshRun ? 1 : existing.attempts() + 1;
            Set<String> callers = callersOf(freshRun ? null : existing, caller);
            if (attempts >= properties.getMaxAttempts() && spreadEnough(caller, callers)) {
                justLocked.set(true);
                // the count resets as the lock goes on, so the attempt after it
                // lifts is the first of a fresh run rather than an instant re-lock
                return new Failures(0, Set.of(), now, now.plus(properties.getLockoutFor()));
            }
            return new Failures(attempts, callers, now, null);
        });
        return justLocked.get();
    }

    /**
     * The addresses this run's failures have come from so far, {@code caller}
     * included. Capped at the threshold: beyond it there is nothing left to
     * learn, and the cap keeps a run fed invented addresses from growing
     * without bound.
     */
    private Set<String> callersOf(Failures existing, String caller) {
        Set<String> seen = existing == null ? Set.of() : existing.callers();
        if (caller == null || seen.contains(caller) || seen.size() >= properties.getMinAddresses()) {
            return seen;
        }
        Set<String> grown = new HashSet<>(seen);
        grown.add(caller);
        return Set.copyOf(grown);
    }

    /**
     * Whether these failures are spread across enough addresses for an account
     * lock. An address key has no caller and locks on the count alone; an
     * unknown address counts as spread enough by itself - see {@link #UNKNOWN_ADDRESS}.
     */
    private boolean spreadEnough(String caller, Set<String> callers) {
        if (caller == null) {
            return true;
        }
        return callers.size() >= properties.getMinAddresses() || callers.contains(UNKNOWN_ADDRESS);
    }

    private static String callerOf(String address) {
        return address == null || address.isBlank() ? UNKNOWN_ADDRESS : address;
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
     * @param callers       the distinct addresses the run's failures came from,
     *                      capped at the spread threshold; empty for address keys
     * @param lastFailureAt when the run was last added to, for expiry
     * @param lockedUntil   when the lock lifts, or null while there is no lock
     */
    private record Failures(int attempts, Set<String> callers, Instant lastFailureAt, Instant lockedUntil) {

        boolean isLocked(Instant now) {
            return lockedUntil != null && now.isBefore(lockedUntil);
        }

        /** True once this run is old enough that the next failure starts a new one. */
        boolean hasExpired(Instant now, Duration window) {
            return lockedUntil != null ? !now.isBefore(lockedUntil) : lastFailureAt.plus(window).isBefore(now);
        }
    }
}
