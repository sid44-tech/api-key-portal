package com.sidbhat.apikeyportal.activity;

/**
 * What happened. The history page groups and colours events by this.
 * <p>
 * Stored as the enum name, so renaming a constant would orphan existing rows -
 * add a new one instead.
 */
public enum ActivityAction {

    KEY_CREATED("Key created", "bi-key", "pill-success"),
    KEY_DELETED("Key deleted", "bi-trash", "pill-danger"),
    INTEGRATION_CONNECTED("Integration connected", "bi-plug", "pill-accent"),
    INTEGRATION_REMOVED("Integration removed", "bi-plug", "pill-danger"),
    INVITATION_SENT("Invitation sent", "bi-envelope", "pill-neutral"),
    ACCOUNT_CREATED("Account created", "bi-person-plus", "pill-success"),
    ACCOUNT_LINKED_TO_SSO("Account linked to SSO", "bi-shield-check", "pill-neutral"),
    SSO_CONFIGURED("Single sign-on changed", "bi-shield-lock", "pill-accent"),
    GROUP_CREATED("Group created", "bi-diagram-3", "pill-neutral");

    private final String label;

    /** Bootstrap icon class shown against the event. */
    private final String icon;

    /** Pill class for the action badge. */
    private final String badgeClass;

    ActivityAction(String label, String icon, String badgeClass) {
        this.label = label;
        this.icon = icon;
        this.badgeClass = badgeClass;
    }

    public String getLabel() {
        return label;
    }

    public String getIcon() {
        return icon;
    }

    public String getBadgeClass() {
        return badgeClass;
    }
}
