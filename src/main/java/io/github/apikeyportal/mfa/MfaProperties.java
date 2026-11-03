package io.github.apikeyportal.mfa;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param required whether every password account must enrol a second factor.
 *                 Off, and two-factor is each person's choice from their
 *                 profile; on, and an account without one is pinned to the
 *                 enrolment page at sign-in until it has one. Accounts that
 *                 sign in through the identity provider are never affected.
 */
@ConfigurationProperties(prefix = "portal.security.mfa")
public record MfaProperties(boolean required) {
}
