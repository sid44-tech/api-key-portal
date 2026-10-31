package com.sidbhat.apikeyportal.activity;

/**
 * What happened. The history page groups and colours events by this.
 * <p>
 * Stored as the enum name, so renaming a constant would orphan existing rows -
 * add a new one instead.
 */
public enum ActivityAction {

    KEY_CREATED("Key created", "bi-key", "bg-success"),
    KEY_DELETED("Key deleted", "bi-trash", "bg-danger"),
    INTEGRATION_CONNECTED("Integration connected", "bi-plug", "bg-primary"),
    INTEGRATION_REMOVED("Integration removed", "bi-plug", "bg-danger"),
    INVITATION_SENT("Invitation sent", "bi-envelope", "bg-secondary"),
    ACCOUNT_CREATED("Account created", "bi-person-plus", "bg-success"),
    ACCOUNT_LINKED_TO_SSO("Account linked to SSO", "bi-shield-check", "bg-secondary"),
    SSO_CONFIGURED("Single sign-on changed", "bi-shield-lock", "bg-primary"),
    GROUP_CREATED("Group created", "bi-diagram-3", "bg-secondary");

    private final String label;

    /** Bootstrap icon class shown against the event. */
    private final String icon;

    /** Bootstrap background class for the action badge. */
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
