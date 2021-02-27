package com.sidbhat.apikeyportal.key;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class ExpirationStatusTest {

    private static final LocalDate TODAY = LocalDate.of(2021, 2, 27);

    @Test
    void nullExpiryNeverExpires() {
        assertThat(ExpirationStatus.of(null, TODAY)).isEqualTo(ExpirationStatus.NEVER);
    }

    @Test
    void pastDateIsExpired() {
        assertThat(ExpirationStatus.of(TODAY.minusDays(1), TODAY)).isEqualTo(ExpirationStatus.EXPIRED);
    }

    @Test
    void expiringTodayIsExpired() {
        assertThat(ExpirationStatus.of(TODAY, TODAY)).isEqualTo(ExpirationStatus.EXPIRED);
    }

    @Test
    void withinWarningWindowIsExpiringSoon() {
        assertThat(ExpirationStatus.of(TODAY.plusDays(10), TODAY)).isEqualTo(ExpirationStatus.EXPIRING_SOON);
    }

    @Test
    void farFutureIsActive() {
        assertThat(ExpirationStatus.of(TODAY.plusDays(90), TODAY)).isEqualTo(ExpirationStatus.ACTIVE);
    }
}
