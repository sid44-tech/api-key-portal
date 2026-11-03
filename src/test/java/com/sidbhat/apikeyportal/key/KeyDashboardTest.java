package com.sidbhat.apikeyportal.key;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.sidbhat.apikeyportal.user.TestUsers;

class KeyDashboardTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    private static final Instant NOW = Instant.parse("2026-11-02T09:00:00Z");

    private static ApiKey key(String name, LocalDate expiresOn, int addedDaysAgo, boolean revealed) {
        ApiKey key = new ApiKey(name, "Stripe", "sk_" + name, TODAY.minusDays(addedDaysAgo), expiresOn);
        key.assignTo(TestUsers.alex(), TestUsers.platformTeam());
        ReflectionTestUtils.setField(key, "addedAt", NOW.minus(addedDaysAgo, ChronoUnit.DAYS));
        if (revealed) {
            ReflectionTestUtils.setField(key, "lastRevealedAt", NOW.minus(1, ChronoUnit.DAYS));
        }
        return key;
    }

    @Test
    void expiringUsesTheSameLineAsTheBadges() {
        ApiKey inAWeek = key("soon", TODAY.plusDays(7), 100, true);
        ApiKey inThirty = key("edge", TODAY.plusDays(30), 100, true);
        ApiKey inThirtyOne = key("later", TODAY.plusDays(31), 100, true);
        ApiKey today = key("today", TODAY, 100, true);
        ApiKey never = key("forever", null, 100, true);

        KeyDashboard dashboard = KeyDashboard.of(List.of(inThirtyOne, inThirty, inAWeek, today, never), TODAY, NOW);

        // soonest first; a key expiring today is already expired, not expiring
        assertThat(dashboard.expiringSoon()).containsExactly(inAWeek, inThirty);
        assertThat(dashboard.expired()).isEqualTo(1);
        assertThat(dashboard.total()).isEqualTo(5);
    }

    @Test
    void neverRevealedMeansOldEnoughToMatterAndStillLive() {
        ApiKey oldUnused = key("old-unused", null, 45, false);
        ApiKey olderUnused = key("older-unused", null, 90, false);
        ApiKey newUnused = key("new", null, 3, false);
        ApiKey oldButUsed = key("used", null, 45, true);
        ApiKey expiredUnused = key("dead", TODAY.minusDays(1), 60, false);
        ApiKey exactlyThirty = key("thirty", null, 30, false);

        KeyDashboard dashboard = KeyDashboard.of(
                List.of(oldUnused, newUnused, oldButUsed, expiredUnused, olderUnused, exactlyThirty), TODAY, NOW);

        // oldest first; the three-day-old key has simply not been needed yet, the
        // expired one is on the expired list already
        assertThat(dashboard.neverRevealed()).containsExactly(olderUnused, oldUnused, exactlyThirty);
    }

    @Test
    void failedReplacementsAreListedLatestFirst() {
        ApiKey earlier = key("earlier", TODAY.plusDays(5), 100, true);
        ApiKey later = key("later", TODAY.plusDays(5), 100, true);
        ApiKey fine = key("fine", TODAY.plusDays(5), 100, true);
        ReflectionTestUtils.setField(earlier, "rotationFailedAt", NOW.minus(2, ChronoUnit.DAYS));
        ReflectionTestUtils.setField(earlier, "rotationFailure", "Azure said no");
        ReflectionTestUtils.setField(later, "rotationFailedAt", NOW.minus(1, ChronoUnit.DAYS));

        KeyDashboard dashboard = KeyDashboard.of(List.of(earlier, fine, later), TODAY, NOW);

        assertThat(dashboard.failedRotations()).containsExactly(later, earlier);
        // the same key can be on two lists: that is two things wrong with it
        assertThat(dashboard.expiringSoon()).hasSize(3);
        assertThat(dashboard.isAllClear()).isFalse();
    }

    @Test
    void allClearWhenNothingNeedsAttention() {
        KeyDashboard dashboard = KeyDashboard.of(List.of(key("fine", TODAY.plusDays(200), 100, true),
                key("new", null, 2, false)), TODAY, NOW);

        assertThat(dashboard.isAllClear()).isTrue();
        assertThat(dashboard.total()).isEqualTo(2);
    }
}
