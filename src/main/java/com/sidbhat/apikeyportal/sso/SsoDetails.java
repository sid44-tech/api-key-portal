package com.sidbhat.apikeyportal.sso;

/**
 * What a super admin fills in on the single sign-on page.
 *
 * @param clientSecret blank on an edit means "keep the stored one"
 */
public record SsoDetails(boolean enabled, String displayName, String issuerUri, String clientId,
        String clientSecret, String scopes, String groupsClaim, String requiredGroup, String adminGroup,
        Long defaultGroupId) {

    @Override
    public String toString() {
        // never include the client secret here, this ends up in logs
        return "SsoDetails{issuer='" + issuerUri + "', clientId='" + clientId + "', enabled=" + enabled + "}";
    }
}
