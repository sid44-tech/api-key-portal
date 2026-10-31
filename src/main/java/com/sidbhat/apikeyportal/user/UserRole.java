package com.sidbhat.apikeyportal.user;

/**
 * What a user is allowed to see. Each role is a superset of the one below it:
 * a super admin sees every account in the portal, a group admin sees their own
 * group, and a member sees the other people in their group.
 * <p>
 * No role lets anyone read someone else's key <em>value</em> - that stays with
 * the person who added the key.
 */
public enum UserRole {

    /** Runs the portal. Sees every group and every account. */
    SUPER_ADMIN("Super admin", "bg-danger"),

    /** Created a group. Sees and administers the accounts in it. */
    GROUP_ADMIN("Group admin", "bg-primary"),

    /** Belongs to a group. Sees the other people in it and the keys they hold. */
    MEMBER("Member", "bg-secondary");

    private final String label;

    /** Bootstrap background class for the role badge. */
    private final String badgeClass;

    UserRole(String label, String badgeClass) {
        this.label = label;
        this.badgeClass = badgeClass;
    }

    public String getLabel() {
        return label;
    }

    public String getBadgeClass() {
        return badgeClass;
    }

    /** The Spring Security authority name, e.g. {@code ROLE_GROUP_ADMIN}. */
    public String getAuthority() {
        return "ROLE_" + name();
    }
}
