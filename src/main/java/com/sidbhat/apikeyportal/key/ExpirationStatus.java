package com.sidbhat.apikeyportal.key;

import java.time.LocalDate;

public enum ExpirationStatus {

    NEVER("Never expires", "pill-neutral"),
    ACTIVE("Active", "pill-success"),
    EXPIRING_SOON("Expiring soon", "pill-warning"),
    EXPIRED("Expired", "pill-danger");

    /** Keys expiring within this many days (inclusive) are flagged as expiring soon. */
    public static final int EXPIRING_SOON_DAYS = 30;

    private final String label;
    private final String badgeClass;

    ExpirationStatus(String label, String badgeClass) {
        this.label = label;
        this.badgeClass = badgeClass;
    }

    public static ExpirationStatus of(LocalDate expiresOn, LocalDate today) {
        if (expiresOn == null) {
            return NEVER;
        }
        if (!expiresOn.isAfter(today)) {
            return EXPIRED;
        }
        if (!expiresOn.isAfter(today.plusDays(EXPIRING_SOON_DAYS))) {
            return EXPIRING_SOON;
        }
        return ACTIVE;
    }

    public String getLabel() {
        return label;
    }

    /** Pill class for the status badge, e.g. {@code pill-warning}. */
    public String getBadgeClass() {
        return badgeClass;
    }
}
