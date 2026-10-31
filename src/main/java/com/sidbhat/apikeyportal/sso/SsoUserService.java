package com.sidbhat.apikeyportal.sso;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sidbhat.apikeyportal.activity.ActivityAction;
import com.sidbhat.apikeyportal.activity.ActivityService;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountRepository;
import com.sidbhat.apikeyportal.user.UserRole;

/**
 * Turns a person arriving from the identity provider into a portal account.
 * <p>
 * This is the gate. Three rules hold however the provider is configured:
 * <ul>
 *   <li><b>Membership of the configured group is required on every sign-in</b>,
 *       not just the first. Removing somebody from the group at the provider
 *       locks them out of the portal straight away, which is the whole point of
 *       managing access there.</li>
 *   <li><b>Super admin is never granted from a claim.</b> Whoever controls the
 *       directory could otherwise hand themselves the one role that administers
 *       every group in the portal.</li>
 *   <li><b>A disabled account stays disabled.</b> Signing in through the
 *       provider must not quietly undo an administrator revoking access here.</li>
 * </ul>
 */
@Service
public class SsoUserService extends OidcUserService {

    private static final Logger log = LoggerFactory.getLogger(SsoUserService.class);

    /**
     * Entra ID replaces the groups claim with this pointer when somebody is in
     * too many groups to fit in a token.
     */
    private static final String CLAIM_NAMES = "_claim_names";

    private final SsoService ssoService;
    private final UserAccountRepository accounts;
    private final ActivityService activity;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public SsoUserService(SsoService ssoService, UserAccountRepository accounts, ActivityService activity,
            PasswordEncoder passwordEncoder) {
        this.ssoService = ssoService;
        this.accounts = accounts;
        this.activity = activity;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);
        SsoConfiguration config = ssoService.find()
                .filter(SsoConfiguration::isEnabled)
                .orElseThrow(() -> refuse(SsoError.NOT_ENABLED));

        Map<String, Object> claims = oidcUser.getClaims();
        requireMembership(claims, config, oidcUser.getSubject());

        // preferred_username is deliberately not used: OIDC says a client must not
        // rely on it being unique or stable, and here the email picks the account.
        String email = UserAccount.normalizeEmail(firstNonBlank(oidcUser.getEmail(),
                stringClaim(claims, "upn")));
        if (email == null) {
            throw refuse(SsoError.NO_EMAIL);
        }
        // Only an explicit "no". Entra does not send the claim at all, so requiring
        // a true would turn the most likely provider away.
        if (Boolean.FALSE.equals(claims.get("email_verified"))) {
            log.warn("Refused SSO sign-in for subject {}: the provider reports the email as unverified",
                    oidcUser.getSubject());
            throw refuse(SsoError.UNVERIFIED_EMAIL);
        }
        String displayName = firstNonBlank(oidcUser.getFullName(), stringClaim(claims, "name"), email);

        UserAccount account = resolve(oidcUser.getSubject(), email, displayName, groupsFrom(claims, config),
                config);
        return new PortalOidcUser(account, oidcUser.getIdToken(), oidcUser.getUserInfo());
    }

    /**
     * Finds the account behind this person, linking or creating one as needed.
     * Matching is by subject first because it is the only stable identifier -
     * an email can be reassigned at the provider, a subject cannot.
     */
    UserAccount resolve(String subject, String email, String displayName, Set<String> groups,
            SsoConfiguration config) {
        UserAccount bySubject = accounts.findBySsoSubject(subject).orElse(null);
        if (bySubject != null) {
            return refresh(bySubject, email, displayName, groups, config);
        }

        UserAccount byEmail = accounts.findByEmailIgnoreCase(email).orElse(null);
        if (byEmail != null) {
            // Somebody invited earlier, signing in through the provider for the first
            // time. Their existing role and group stand.
            if (byEmail.isSsoLinked()) {
                // a second provider identity presenting an email that already belongs
                // to a linked account: re-pointing it would hand that account over
                log.warn("Refused SSO sign-in for subject {}: {} is already linked to another subject",
                        subject, byEmail);
                throw refuse(SsoError.ALREADY_LINKED);
            }
            if (byEmail.isSuperAdmin()) {
                // the one account that administers every group stays on its password,
                // so whoever runs the directory cannot take the portal with it
                log.warn("Refused SSO sign-in for subject {}: {} is the super admin", subject, byEmail);
                throw refuse(SsoError.SUPER_ADMIN_NOT_LINKABLE);
            }
            byEmail.setSsoSubject(subject);
            UserAccount linked = refresh(byEmail, email, displayName, groups, config);
            log.info("Linked {} to SSO subject", linked);
            activity.record(ActivityAction.ACCOUNT_LINKED_TO_SSO, linked, linked.getId(),
                    linked.getDisplayName(), config.getDisplayName());
            return linked;
        }

        if (config.getDefaultGroup() == null) {
            throw refuse(SsoError.NO_DEFAULT_GROUP);
        }
        UserAccount created = new UserAccount(email, displayName, unusablePassword(),
                roleFor(groups, config, UserRole.MEMBER), config.getDefaultGroup());
        created.setSsoSubject(subject);
        // there is no handed-out password behind this account to replace
        created.setMustChangePassword(false);
        UserAccount saved = accounts.save(created);
        log.info("Created {} from an SSO sign-in", saved);
        activity.record(ActivityAction.ACCOUNT_CREATED, saved, saved.getId(), saved.getDisplayName(),
                "Signed in through " + config.getDisplayName());
        return saved;
    }

    /** Brings an existing account back in step with the provider. */
    private UserAccount refresh(UserAccount account, String email, String displayName, Set<String> groups,
            SsoConfiguration config) {
        if (!account.isEnabled()) {
            log.warn("Refused SSO sign-in for {}: the account is disabled", account);
            throw refuse(SsoError.ACCOUNT_DISABLED);
        }
        account.setEmail(email);
        account.setDisplayName(displayName);
        // The provider owns the group-admin flag only when an admin group is set,
        // and never owns super admin. Without one, roles stay the portal's own.
        if (config.getAdminGroup() != null && !account.isSuperAdmin()) {
            account.setRole(roleFor(groups, config, UserRole.MEMBER));
        }
        return accounts.save(account);
    }

    private static UserRole roleFor(Set<String> groups, SsoConfiguration config, UserRole fallback) {
        if (config.getAdminGroup() != null && groups.contains(normalize(config.getAdminGroup()))) {
            return UserRole.GROUP_ADMIN;
        }
        return fallback;
    }

    void requireMembership(Map<String, Object> claims, SsoConfiguration config, String subject) {
        if (isGroupsOverage(claims, config)) {
            log.warn("Refused SSO sign-in for subject {}: the provider sent a group overage claim instead of "
                    + "group membership", subject);
            throw refuse(SsoError.TOO_MANY_GROUPS);
        }
        Set<String> groups = groupsFrom(claims, config);
        if (!groups.contains(normalize(config.getRequiredGroup()))) {
            log.warn("Refused SSO sign-in for subject {}: not a member of the required group", subject);
            throw refuse(SsoError.NOT_IN_GROUP);
        }
    }

    /**
     * True when the provider said "there are groups, but too many to send". Only
     * meaningful when the claim is otherwise absent.
     */
    private static boolean isGroupsOverage(Map<String, Object> claims, SsoConfiguration config) {
        return claims.get(config.getGroupsClaim()) == null
                && claims.get(CLAIM_NAMES) instanceof Map<?, ?> names
                && names.containsKey(config.getGroupsClaim());
    }

    /**
     * Group membership, lower-cased for comparison. Providers send either a list
     * or - when there is exactly one - a bare string, and Entra sends group
     * object IDs while others send names; both are matched the same way.
     */
    static Set<String> groupsFrom(Map<String, Object> claims, SsoConfiguration config) {
        Object claim = claims.get(config.getGroupsClaim());
        Set<String> groups = new LinkedHashSet<>();
        if (claim instanceof Collection<?> values) {
            values.forEach(value -> groups.add(normalize(String.valueOf(value))));
        } else if (claim instanceof String single && !single.isBlank()) {
            groups.add(normalize(single));
        }
        return groups;
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String stringClaim(Map<String, Object> claims, String name) {
        return claims.get(name) instanceof String value ? value : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * A real BCrypt hash of something nobody knows, so an SSO account can never be
     * signed into with the password form. A null or placeholder would have to be
     * special-cased in the password encoder, which is exactly where such things
     * go wrong.
     */
    private String unusablePassword() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return passwordEncoder.encode(Base64.getEncoder().encodeToString(bytes));
    }

    private static OAuth2AuthenticationException refuse(SsoError error) {
        return new OAuth2AuthenticationException(new OAuth2Error(error.getCode(), error.getMessage(), null),
                error.getMessage());
    }
}
