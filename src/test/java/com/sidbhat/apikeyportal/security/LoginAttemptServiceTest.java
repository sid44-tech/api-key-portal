package com.sidbhat.apikeyportal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sidbhat.apikeyportal.logging.AuditLog;

class LoginAttemptServiceTest {

    private static final String ALEX = "alex@example.com";
    private static final String SAM = "sam@example.com";
    private static final String ADDRESS = "198.51.100.7";
    private static final String OTHER_ADDRESS = "203.0.113.9";

    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    private Instant now = Instant.parse("2026-10-31T09:00:00Z");

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        // a clock the test moves by hand, so nothing here waits for a real lockout
        service = new LoginAttemptService(new LockoutProperties(5, LOCKOUT), new AuditLog(), new MovableClock());
    }

    /** Reads {@link #now}, which the test advances. */
    private class MovableClock extends Clock {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private void fail(int times, String email, String address) {
        for (int i = 0; i < times; i++) {
            service.recordFailure(email, address);
        }
    }

    @Test
    void aFewWrongGuessesAreNotALockout() {
        fail(4, ALEX, ADDRESS);

        assertThat(service.isLockedOut(ALEX, ADDRESS)).isFalse();
    }

    @Test
    void theFifthWrongGuessLocksTheAccount() {
        fail(5, ALEX, ADDRESS);

        assertThat(service.isLockedOut(ALEX, ADDRESS)).isTrue();
        assertThat(service.lockedUntil(ALEX, ADDRESS)).isEqualTo(now.plus(LOCKOUT));
    }

    @Test
    void theAccountIsLockedWhereverTheGuessesComeFrom() {
        // one address each is how a botnet stays under a per-address limit
        for (int i = 0; i < 5; i++) {
            service.recordFailure(ALEX, "198.51.100." + i);
        }

        assertThat(service.isLockedOut(ALEX, "203.0.113.200")).isTrue();
    }

    @Test
    void oneCallerWorkingDownAListOfAccountsIsLockedToo() {
        // never more than two guesses at any one account, so no account locks
        service.recordFailure(ALEX, ADDRESS);
        service.recordFailure(SAM, ADDRESS);
        service.recordFailure("kim@example.com", ADDRESS);
        service.recordFailure("jo@example.com", ADDRESS);
        service.recordFailure("ash@example.com", ADDRESS);

        assertThat(service.isLockedOut("brand-new@example.com", ADDRESS)).isTrue();
        // and only for that caller
        assertThat(service.isLockedOut("brand-new@example.com", OTHER_ADDRESS)).isFalse();
    }

    @Test
    void anAccountLockedFromOneAddressIsNotLockedForEverybodyElsesAccounts() {
        fail(5, ALEX, ADDRESS);

        assertThat(service.isLockedOut(SAM, OTHER_ADDRESS)).isFalse();
    }

    @Test
    void theLockLiftsOnItsOwn() {
        fail(5, ALEX, ADDRESS);

        now = now.plus(LOCKOUT);

        assertThat(service.isLockedOut(ALEX, ADDRESS)).isFalse();
    }

    @Test
    void theCountStartsAfreshAfterALockSoOneTypoDoesNotRelockImmediately() {
        fail(5, ALEX, ADDRESS);
        now = now.plus(LOCKOUT);

        fail(4, ALEX, ADDRESS);

        assertThat(service.isLockedOut(ALEX, ADDRESS)).isFalse();
    }

    @Test
    void occasionalTyposSpreadOutOverTimeNeverAddUpToALockout() {
        for (int i = 0; i < 20; i++) {
            service.recordFailure(ALEX, ADDRESS);
            now = now.plus(LOCKOUT).plusSeconds(1);
            assertThat(service.isLockedOut(ALEX, ADDRESS)).isFalse();
        }
    }

    @Test
    void signingInSuccessfullyForgetsTheFailuresBehindIt() {
        fail(4, ALEX, ADDRESS);

        service.recordSuccess(ALEX, ADDRESS);
        fail(4, ALEX, ADDRESS);

        assertThat(service.isLockedOut(ALEX, ADDRESS)).isFalse();
    }

    @Test
    void aSuccessDoesNotUnlockAnAccountThatIsAlreadyLocked() {
        // the lock is checked before any password is, so this cannot happen
        // through the portal - but it must not be a way out if it ever could
        fail(5, ALEX, ADDRESS);
        fail(5, SAM, OTHER_ADDRESS);

        service.recordSuccess(SAM, OTHER_ADDRESS);

        assertThat(service.isLockedOut(ALEX, ADDRESS)).isTrue();
    }

    @Test
    void guessesAtABlankEmailStillCountAgainstTheAddress() {
        fail(5, "  ", ADDRESS);

        assertThat(service.isLockedOut(ALEX, ADDRESS)).isTrue();
        assertThat(service.isLockedOut(ALEX, OTHER_ADDRESS)).isFalse();
    }

    @Test
    void theAccountIsMatchedHoweverTheEmailWasTyped() {
        fail(5, "ALEX@Example.com ", ADDRESS);

        assertThat(service.isLockedOut(ALEX, OTHER_ADDRESS)).isTrue();
    }

    @Test
    void aCallerWithNoAddressIsStillCountedAgainstTheirAccount() {
        fail(5, ALEX, null);

        assertThat(service.isLockedOut(ALEX, null)).isTrue();
        assertThat(service.isLockedOut(SAM, null)).isFalse();
    }

    @Test
    void lockedUntilIsTheLaterOfTheTwoLocks() {
        // the account, from five different addresses, so no address locks with it
        for (int i = 0; i < 5; i++) {
            service.recordFailure(ALEX, "198.51.100." + i);
        }

        // then, a minute later, this address, across five different accounts
        now = now.plusSeconds(60);
        Instant addressLock = now.plus(LOCKOUT);
        for (int i = 0; i < 5; i++) {
            service.recordFailure("someone" + i + "@example.com", ADDRESS);
        }

        assertThat(service.lockedUntil(ALEX, ADDRESS)).isEqualTo(addressLock);
    }

    @Test
    void nothingIsLockedToStartWith() {
        assertThat(service.isLockedOut(ALEX, ADDRESS)).isFalse();
        assertThat(service.lockedUntil(ALEX, ADDRESS)).isNull();
    }
}
