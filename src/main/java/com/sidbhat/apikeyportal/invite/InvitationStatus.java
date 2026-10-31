package com.sidbhat.apikeyportal.invite;

/**
 * Where an invitation stands, worked out from its timestamps rather than stored,
 * so an invite expires on its own without anything having to run.
 */
public enum InvitationStatus {

    /** Sent, still within its day, not yet used. */
    PENDING("Pending", "bg-primary"),

    /** The account exists. */
    ACCEPTED("Accepted", "bg-success"),

    /** The day elapsed. A fresh invitation can be sent to the same address. */
    EXPIRED("Expired", "bg-secondary"),

    /** Withdrawn by an administrator. */
    REVOKED("Revoked", "bg-dark");

    private final String label;

    /** Bootstrap background class for the status badge. */
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
