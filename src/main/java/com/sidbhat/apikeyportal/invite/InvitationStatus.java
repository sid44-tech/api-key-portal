package com.sidbhat.apikeyportal.invite;

/**
 * Where an invitation stands, worked out from its timestamps rather than stored,
 * so an invite expires on its own without anything having to run.
 */
public enum InvitationStatus {

    /** Sent, still within its day, not yet used. */
    PENDING("Pending", "pill-accent"),

    /** The account exists. */
    ACCEPTED("Accepted", "pill-success"),

    /** The day elapsed. A fresh invitation can be sent to the same address. */
    EXPIRED("Expired", "pill-neutral"),

    /** Withdrawn by an administrator. */
    REVOKED("Revoked", "pill-warning");

    private final String label;

    /** Pill class for the status badge. */
    private final String badgeClass;

    InvitationStatus(String label, String badgeClass) {
        this.label = label;
        this.badgeClass = badgeClass;
    }

    public String getLabel() {
        return label;
    }

    public String getBadgeClass() {
        return badgeClass;
    }
}
