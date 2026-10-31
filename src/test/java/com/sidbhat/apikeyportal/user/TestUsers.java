package com.sidbhat.apikeyportal.user;

import org.springframework.test.util.ReflectionTestUtils;

/**
 * Ready-made accounts for tests. Two groups: the super admin sits on their own,
 * and Alex (group admin) shares "Platform team" with Sam (member).
 * <p>
 * Ids are set by reflection because they are normally assigned by the database,
 * and most tests here never touch one.
 */
public final class TestUsers {

    private TestUsers() {
    }

    public static UserGroup group(long id, String name) {
        UserGroup group = new UserGroup(name);
        ReflectionTestUtils.setField(group, "id", id);
        return group;
    }

    public static UserAccount account(long id, String email, String displayName, UserRole role, UserGroup group) {
        UserAccount account = new UserAccount(email, displayName, "{bcrypt}not-a-real-hash", role, group);
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    public static UserGroup adminGroup() {
        return group(1L, "Administrators");
    }

    public static UserGroup platformTeam() {
        return group(2L, "Platform team");
    }

    public static UserAccount superAdmin() {
        return account(1L, "admin@example.com", "Administrator", UserRole.SUPER_ADMIN, adminGroup());
    }

    /** Group admin of "Platform team". */
    public static UserAccount alex() {
        return account(2L, "alex@example.com", "Alex Carter", UserRole.GROUP_ADMIN, platformTeam());
    }

    /** Member of "Platform team", so a colleague of {@link #alex()}. */
    public static UserAccount sam() {
        return account(3L, "sam@example.com", "Sam Diaz", UserRole.MEMBER, platformTeam());
    }

    /** Member of a different group, so nothing of Alex's is visible to them. */
    public static UserAccount outsider() {
        return account(4L, "jo@example.com", "Jo Okafor", UserRole.GROUP_ADMIN, group(3L, "The Okafors"));
    }

    public static PortalUser principal(UserAccount account) {
        return new PortalUser(account);
    }

    /** Looks a fixture up by the short name used in {@link WithPortalUser}. */
    public static UserAccount byName(String name) {
        return switch (name) {
            case "superadmin" -> superAdmin();
            case "sam" -> sam();
            case "outsider" -> outsider();
            case "alex" -> alex();
            default -> throw new IllegalArgumentException("No test user called " + name);
        };
    }

    /** The account behind a principal, as {@code UserAccountService.getCurrent} would return it. */
    public static UserAccount accountFor(PortalUser principal) {
        return switch (principal.getEmail()) {
            case "admin@example.com" -> superAdmin();
            case "sam@example.com" -> sam();
            case "jo@example.com" -> outsider();
            default -> alex();
        };
    }
}
