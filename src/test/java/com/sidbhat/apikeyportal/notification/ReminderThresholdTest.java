package com.sidbhat.apikeyportal.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Which reminder a key gets is decided entirely here, so the edges matter.
 */
class ReminderThresholdTest {

    @Test
    void eachBandIsTheTightestThresholdTheRemainingDaysFitUnder() {
        assertThat(ReminderThreshold.forDaysRemaining(30)).contains(ReminderThreshold.ONE_MONTH);
        assertThat(ReminderThreshold.forDaysRemaining(15)).contains(ReminderThreshold.ONE_MONTH);
        assertThat(ReminderThreshold.forDaysRemaining(14)).contains(ReminderThreshold.TWO_WEEKS);
        assertThat(ReminderThreshold.forDaysRemaining(8)).contains(ReminderThreshold.TWO_WEEKS);
        assertThat(ReminderThreshold.forDaysRemaining(7)).contains(ReminderThreshold.ONE_WEEK);
        assertThat(ReminderThreshold.forDaysRemaining(2)).contains(ReminderThreshold.ONE_WEEK);
        assertThat(ReminderThreshold.forDaysRemaining(1)).contains(ReminderThreshold.ONE_DAY);
    }

    @Test
    void aKeyExpiringTodayStillGetsTheLastReminder() {
        assertThat(ReminderThreshold.forDaysRemaining(0)).contains(ReminderThreshold.ONE_DAY);
    }

    @Test
    void anExpiredKeyGetsNoReminderAtAll() {
        // that is a different message, and nagging about the past helps nobody
        assertThat(ReminderThreshold.forDaysRemaining(-1)).isEmpty();
        assertThat(ReminderThreshold.forDaysRemaining(-400)).isEmpty();
    }

    @Test
    void nothingHappensBeforeTheMonthMark() {
        assertThat(ReminderThreshold.forDaysRemaining(31)).isEmpty();
        assertThat(ReminderThreshold.forDaysRemaining(365)).isEmpty();
    }

    @Test
    void theHorizonIsTheFurthestThreshold() {
        assertThat(ReminderThreshold.horizonDays()).isEqualTo(30);
    }
}
