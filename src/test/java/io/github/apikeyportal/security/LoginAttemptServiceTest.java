package io.github.apikeyportal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.apikeyportal.logging.AuditLog;

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
        service = new LoginAttemptService(new LockoutProperties(5, LOCKOUT, 3), new AuditLog(), new MovableClock());
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
    void theFifthWrongGuessLocksTheCallerNotTheAccount() {
        fail(5, ALEX, ADDRESS);

        // the caller is refused - but only the caller. Anyone can type anyone's
        // email at the sign-in page, so one address's failures must never lock
        // the account itself: that was a free denial of service on any account,
        // including the super admin, the emergency door when SSO is broken.
        assertThat(service.isLockedOut(ALEX, ADDRESS)).isTrue();
        assertThat(service.lockedUntil(ALEX, ADDRESS)).isEqualTo(now.plus(LOCKOUT));
        assertThat(service.isLockedOut(ALEX, OTHER_ADDRESS)).isFalse();
    }

    @Test
    void twoAddressesAreStillTooFewToLockAnAccount() {
        fail(3, ALEX, ADDRESS);
        fail(3, ALEX, OTHER_ADDRESS);

        // six failures, but a prank needs only a phone and a laptop to reach two
        // addresses; the account lock waits for a genuinely distributed spread
        assertThat(service.isLockedOut(ALEX, "203.0.113.200")).isFalse();
    }

    @Test
    void enoughFailuresFromEnoughAddressesLockTheAccountItself() {
        service.recordFailure(ALEX, ADDRESS);
        service.recordFailure(ALEX, ADDRESS);
        service.recordFailure(ALEX, OTHER_ADDRESS);
        service.recordFailure(ALEX, OTHER_ADDRESS);
        service.recordFailure(ALEX, "198.51.100.77");

        // five failures across three addresses: the distributed pattern the
        // account lock exists for, so it engages everywhere
        assertThat(service.isLockedOut(ALEX, "203.0.113.200")).isTrue();
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
    void aMinAddressesOfOneRestoresLockingOnTheCountAlone() {
        service = new LoginAttemptService(new LockoutProperties(5, LOCKOUT, 1), new AuditLog(),
                new MovableClock());

        fail(5, ALEX, ADDRESS);

        assertThat(service.isLockedOut(ALEX, OTHER_ADDRESS)).isTrue();
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
    void signingInSuccessfullyForgetsTheFailuresAgainstTheAccount() {
        fail(4, ALEX, ADDRESS);

        service.recordSuccess(ALEX, ADDRESS);
        // four more, spread across enough addresses that a run of eight would lock
        for (int i = 0; i < 4; i++) {
            service.recordFailure(ALEX, "198.51.100." + i);
        }

        // a fresh run of four, not a continued run of eight
        assertThat(service.isLockedOut(ALEX, "203.0.113.200")).isFalse();
    }

    @Test
    void signingInSuccessfullyDoesNotForgetTheFailuresFromTheAddress() {
        // four guesses at Sam's account from here...
        fail(4, SAM, ADDRESS);
        // ...then a sign-in to the attacker's own, perfectly valid account
        service.recordSuccess(ALEX, ADDRESS);
        // ...and one more guess, at somebody else again
        service.recordFailure("kim@example.com", ADDRESS);

        // knowing one password proves nothing about the other guesses: the fifth
        // failure from this address locks it, as it would have without the sign-in
        assertThat(service.isLockedOut(SAM, ADDRESS)).isTrue();
        assertThat(service.isLockedOut(ALEX, ADDRESS)).isTrue();
        // and only this address - Sam's account is not what got locked
        assertThat(service.isLockedOut(SAM, OTHER_ADDRESS)).isFalse();
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
        // across enough addresses for an account lock, however the email is cased
        fail(2, "ALEX@Example.com ", ADDRESS);
        fail(2, "alex@EXAMPLE.COM", OTHER_ADDRESS);
        fail(1, " Alex@example.com", "198.51.100.77");

        assertThat(service.isLockedOut(ALEX, "203.0.113.200")).isTrue();
    }

    @Test
    void addressesAreCountedOncePerRunHoweverOftenTheyFail() {
        // two addresses hammering away never add up to three
        fail(10, ALEX, ADDRESS);
        fail(10, ALEX, OTHER_ADDRESS);

        assertThat(service.isLockedOut(ALEX, "203.0.113.200")).isFalse();

        // until a third joins in and the count is already over the limit
        service.recordFailure(ALEX, "198.51.100.77");
        assertThat(service.isLockedOut(ALEX, "203.0.113.200")).isTrue();
    }

    @Test
    void aCallerWithNoAddressIsStillCountedAgainstTheirAccount() {
        // no address means no per-address lock and no way to tell callers apart,
        // so the account lock falls back to the count alone - towards stopping a
        // guessing run, not towards availability
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

    @Test
    void aLockIsAnnouncedToTheChatChannelsThatAskedForIt() {
        io.github.apikeyportal.alerts.AlertNotifier alerts =
                org.mockito.Mockito.mock(io.github.apikeyportal.alerts.AlertNotifier.class);
        service = new LoginAttemptService(new LockoutProperties(5, LOCKOUT, 3), new AuditLog(), new MovableClock(),
                alerts);

        // five from one address: the address locks, the account does not
        fail(5, ALEX, ADDRESS);
        org.mockito.Mockito.verify(alerts).addressLockedOut(ADDRESS, now.plus(LOCKOUT));
        org.mockito.Mockito.verify(alerts, org.mockito.Mockito.never()).accountLockedOut(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        // spread across enough addresses: the account locks too
        for (int i = 0; i < 5; i++) {
            service.recordFailure(SAM, "198.51.100." + i);
        }
        org.mockito.Mockito.verify(alerts).accountLockedOut(SAM, now.plus(LOCKOUT));
    }
}
