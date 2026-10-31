package com.sidbhat.apikeyportal.notification;

import java.util.Arrays;
import java.util.Optional;

/**
 * The points before expiry at which the owner is emailed.
 * <p>
 * A key gets at most one email per threshold, and only for the threshold it is
 * currently inside: a key added with five days left gets the one-week email, not
 * a month's worth at once. That also means a missed day (the portal was down)
 * catches up with a single email rather than a backlog.
 */
public enum ReminderThreshold {

    ONE_MONTH(30, "a month"),
    TWO_WEEKS(14, "two weeks"),
    ONE_WEEK(7, "a week"),
    ONE_DAY(1, "a day");

    private final int days;
    private final String label;

    ReminderThreshold(int days, String label) {
        this.days = days;
        this.label = label;
    }

    public int getDays() {
        return days;
    }

    /** As in "expires in a week". */
    public String getLabel() {
        return label;
    }

    /** The furthest-out threshold, which bounds the daily sweep. */
    public static int horizonDays() {
        return ONE_MONTH.days;
    }

    /**
     * The threshold a key is currently inside, i.e. the tightest one its remaining
     * days fit under. Empty once the key has expired - that is a different message.
     */
    public static Optional<ReminderThreshold> forDaysRemaining(long daysRemaining) {
        if (daysRemaining < 0 || daysRemaining > horizonDays()) {
            return Optional.empty();
        }
        // smallest threshold that still covers the remaining days
        return Arrays.stream(values())
                .filter(threshold -> daysRemaining <= threshold.days)
                .reduce((first, second) -> second);
    }
}
