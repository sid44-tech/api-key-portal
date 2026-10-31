package com.sidbhat.apikeyportal.user;

import org.springframework.security.core.AuthenticationException;

/**
 * Thrown when somebody tries the password form for an account that signs in
 * through the identity provider.
 * <p>
 * Refusing matters rather than being tidy: the directory group is re-checked on
 * every SSO sign-in, so leaving a password working would be a way around it -
 * and around whatever MFA the provider enforces - for anyone who had one before
 * their account was linked.
 */
public class SsoSignInRequiredException extends AuthenticationException {

    public SsoSignInRequiredException(String message) {
        super(message);
    }
}
