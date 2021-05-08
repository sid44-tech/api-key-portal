package com.sidbhat.apikeyportal.key;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class KeySummaryTest {

    @Test
    void countsKeysByStatus() {
        LocalDate today = LocalDate.now();
        KeySummary summary = KeySummary.of(List.of(
                new ApiKey("a", "s", "v", today.minusYears(1), null),
                new ApiKey("b", "s", "v", today.minusYears(1), today.plusYears(1)),
                new ApiKey("c", "s", "v", today.minusYears(1), today.plusDays(3)),
                new ApiKey("d", "s", "v", today.minusYears(1), today.minusDays(3)),
                new ApiKey("e", "s", "v", today.minusYears(1), today)));

        assertThat(summary.getTotal()).isEqualTo(5);
        assertThat(summary.getExpiringSoon()).isEqualTo(1);
        assertThat(summary.getExpired()).isEqualTo(2);
    }
}
