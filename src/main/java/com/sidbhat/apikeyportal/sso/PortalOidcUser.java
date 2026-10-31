package com.sidbhat.apikeyportal.sso;

import java.util.Map;

import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;

/**
 * The signed-in principal for somebody who arrived through SSO.
 * <p>
 * It extends {@link PortalUser} rather than replacing it so that the entire app
 * - every {@code @AuthenticationPrincipal PortalUser} in every controller -
 * carries on working unchanged, and SSO is invisible past the sign-in itself.
 */
public class PortalOidcUser extends PortalUser implements OidcUser {

    private final OidcIdToken idToken;
    private final OidcUserInfo userInfo;

    public PortalOidcUser(UserAccount account, OidcIdToken idToken, OidcUserInfo userInfo) {
        super(account);
        this.idToken = idToken;
        this.userInfo = userInfo;
    }

    @Override
    public Map<String, Object> getClaims() {
        return idToken.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return userInfo;
    }

    @Override
    public OidcIdToken getIdToken() {
        return idToken;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return idToken.getClaims();
    }

    /**
     * The email, so the sign-in audit records the same name a password sign-in
     * would - {@code LoginAuditListener} looks the account up by it.
     */
    @Override
    public String getName() {
        return getEmail();
    }
}
