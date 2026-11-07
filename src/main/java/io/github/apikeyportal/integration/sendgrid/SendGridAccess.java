package io.github.apikeyportal.integration.sendgrid;

import java.util.List;

import io.github.apikeyportal.integration.ProvisioningException;

/**
 * What keys minted through a SendGrid integration may do, chosen in the setup
 * wizard and stored as the integration's tenant id.
 */
public enum SendGridAccess {

    /** Send email and nothing else: what almost every app needs. */
    MAIL_SEND("mail-send", "Mail Send only"),

    /** Everything the portal's own key can do, except managing API keys. */
    INHERIT("inherit", "Same as the portal's key, minus key management");

    private static final String KEY_MANAGEMENT_PREFIX = "api_keys.";

    private final String code;
    private final String label;

    SendGridAccess(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static SendGridAccess fromCode(String code) {
        for (SendGridAccess access : values()) {
            if (access.code.equals(code)) {
                return access;
            }
        }
        throw new IllegalArgumentException("Unknown SendGrid access: " + code);
    }

    /**
     * The scopes a minted key gets, given what the portal's key holds. A minted
     * key never gets key management: what the portal hands out cannot mint more.
     */
    public List<String> scopesFor(List<String> portalScopes) {
        List<String> scopes = switch (this) {
            case MAIL_SEND -> List.of("mail.send");
            case INHERIT -> portalScopes.stream().filter(s -> !s.startsWith(KEY_MANAGEMENT_PREFIX)).toList();
        };
        if (!portalScopes.containsAll(scopes) || scopes.isEmpty()) {
            throw new ProvisioningException("The portal's SendGrid key cannot grant "
                    + (this == MAIL_SEND ? "Mail Send" : "any scopes beyond key management")
                    + " - give it that permission in SendGrid");
        }
        return scopes;
    }

    /** The scopes the portal's own key must hold to work at all. */
    public static void requireKeyManagement(List<String> portalScopes) {
        if (!portalScopes.contains("api_keys.create") || !portalScopes.contains("api_keys.delete")) {
            throw new ProvisioningException("That SendGrid key cannot manage API keys - it needs Full Access, "
                    + "or Restricted Access with API Keys set to Full Access");
        }
    }
}
