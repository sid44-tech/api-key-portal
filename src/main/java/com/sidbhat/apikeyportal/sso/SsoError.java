package com.sidbhat.apikeyportal.sso;

/**
 * Why an SSO sign-in was refused.
 * <p>
 * These reach the sign-in page as a query parameter so the person is told
 * something useful. "Invalid email or password" would be actively misleading
 * when the real answer is "ask IT to add you to the access group" - and unlike
 * a password failure, none of these leak anything: the person has already
 * proved who they are to the identity provider.
 */
public enum SsoError {

    NOT_ENABLED("Single sign-on is not switched on for this portal."),

    NOT_IN_GROUP("Your account isn't a member of the group that grants access to this portal. "
            + "Ask your IT administrator to add you, then try again."),

    /**
     * Entra ID drops the groups claim entirely once somebody is in more than
     * about 200 groups, replacing it with a pointer to the Graph API. Denying
     * them for "not being in the group" would send the administrator hunting
     * for the wrong problem.
     */
    TOO_MANY_GROUPS("Your identity provider did not send your group membership because you are in too many "
            + "groups. An administrator needs to configure the provider to emit only assigned groups."),

    NO_EMAIL("Your identity provider did not send an email address, which the portal needs to identify you."),

    UNVERIFIED_EMAIL("Your identity provider says your email address is not verified, so the portal cannot "
            + "match you to an account. Verify it with your provider and try again."),

    /**
     * Two provider identities presenting the same email. Re-pointing the account
     * at whoever signed in last would hand it to them, so the portal refuses and
     * leaves it to an administrator.
     */
    ALREADY_LINKED("This portal account is already linked to a different account at your identity provider. "
            + "Ask an administrator to sort it out."),

    /**
     * The super admin is the break-glass account: it stays on a password so a
     * broken or hostile provider cannot take the portal with it.
     */
    SUPER_ADMIN_NOT_LINKABLE("The super admin account signs in with its portal password, not single sign-on."),

    USE_SSO("This account signs in through your organisation's identity provider. "
            + "Use the single sign-on button above."),

    ACCOUNT_DISABLED("Your portal account has been disabled. Ask an administrator to re-enable it."),

    NO_DEFAULT_GROUP("Single sign-on has no portal group to put new accounts in. "
            + "A super admin needs to pick one on the single sign-on page."),

    FAILED("Single sign-on did not complete. Please try again, or ask an administrator to check the setup.");

    private final String message;

    SsoError(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    /** The value used in {@code /login?ssoError=...}. */
    public String getCode() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static SsoError fromCode(String code) {
        for (SsoError error : values()) {
            if (error.getCode().equals(code)) {
                return error;
            }
        }
        return FAILED;
    }
}
