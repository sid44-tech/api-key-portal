package com.sidbhat.apikeyportal.sso;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.security.access.AccessDeniedException;

import com.sidbhat.apikeyportal.user.SsoStatus;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserGroup;

/**
 * The portal's single sign-on connection: reading it, saving it, and turning it
 * into the {@link ClientRegistration} Spring Security signs people in with.
 * <p>
 * The connection lives in the database rather than in configuration so a super
 * admin can set it up through the portal without a redeploy, which is how this
 * is done in the enterprise applications people are used to.
 */
@Service
@Transactional(readOnly = true)
public class SsoService implements SsoStatus {

    private static final Logger log = LoggerFactory.getLogger(SsoService.class);

    /** The one registration id; it appears in the redirect URI the admin registers. */
    public static final String REGISTRATION_ID = "sso";

    private final SsoConfigurationRepository repository;

    /**
     * The built registration, kept because building one performs OIDC discovery -
     * an outbound HTTP call that must not happen on every sign-in. Replaced
     * whenever the configuration is saved, and keyed by the configuration's
     * {@code updatedAt} so a change in another instance is picked up too.
     */
    private final AtomicReference<CachedRegistration> cached = new AtomicReference<>();

    public SsoService(SsoConfigurationRepository repository) {
        this.repository = repository;
    }

    public Optional<SsoConfiguration> find() {
        return repository.findFirstByOrderByIdAsc();
    }

    /** True if people should be offered the SSO button on the sign-in page. */
    @Override
    public boolean isEnabled() {
        return find().filter(SsoConfiguration::isEnabled).isPresent();
    }

    /** The label for the sign-in button, e.g. "Contoso". */
    public Optional<String> signInLabel() {
        return find().filter(SsoConfiguration::isEnabled).map(SsoConfiguration::getDisplayName);
    }

    /**
     * The registration Spring Security needs, or empty when SSO is off. Cached;
     * see {@link #cached}.
     */
    public Optional<ClientRegistration> activeRegistration() {
        Optional<SsoConfiguration> configuration = find().filter(SsoConfiguration::isEnabled);
        if (configuration.isEmpty()) {
            cached.set(null);
            return Optional.empty();
        }
        SsoConfiguration config = configuration.get();
        CachedRegistration current = cached.get();
        if (current != null && current.matches(config)) {
            return Optional.of(current.registration());
        }
        ClientRegistration built = build(config);
        cached.set(new CachedRegistration(config.getId(), config.getUpdatedAt(), built));
        return Optional.of(built);
    }

    /**
     * Saves the connection, verifying the issuer first.
     * <p>
     * Discovery is attempted before anything is stored, so a saved connection is
     * known to point at a real provider - the same rule the service integrations
     * follow. An unreachable or wrong issuer is by far the most common mistake
     * and this is where it surfaces, rather than on somebody's first sign-in.
     *
     * @throws SsoException if the issuer cannot be discovered
     */
    @Transactional
    public SsoConfiguration save(SsoDetails details, UserGroup defaultGroup, UserAccount actor) {
        requireSuperAdmin(actor);
        SsoConfiguration config = find().orElseGet(() -> new SsoConfiguration(details.displayName(),
                details.issuerUri(), details.clientId(), details.clientSecret()));
        config.setDisplayName(details.displayName().trim());
        config.setIssuerUri(details.issuerUri().trim());
        config.setClientId(details.clientId().trim());
        // a blank secret on an existing connection means "keep the stored one",
        // so the admin can edit the other fields without re-entering it
        if (details.clientSecret() != null && !details.clientSecret().isBlank()) {
            config.setClientSecret(details.clientSecret().trim());
        }
        config.setScopes(details.scopes() == null || details.scopes().isBlank()
                ? SsoConfiguration.DEFAULT_SCOPES : details.scopes().trim());
        config.setGroupsClaim(details.groupsClaim() == null || details.groupsClaim().isBlank()
                ? SsoConfiguration.DEFAULT_GROUPS_CLAIM : details.groupsClaim().trim());
        config.setRequiredGroup(details.requiredGroup().trim());
        config.setAdminGroup(trimToNull(details.adminGroup()));
        config.setDefaultGroup(defaultGroup);
        config.setEnabled(details.enabled());

        if (config.isEnabled()) {
            // throws before anything is written if the provider can't be reached
            discover(config);
        }
        SsoConfiguration saved = repository.save(config);
        cached.set(null);
        log.info("Saved SSO connection {}", saved);
        return saved;
    }

    /** Turns the connection off without discarding it, so it can be switched back on. */
    @Transactional
    public void disable(UserAccount actor) {
        requireSuperAdmin(actor);
        find().ifPresent(config -> {
            config.setEnabled(false);
            repository.save(config);
            cached.set(null);
            log.info("Disabled SSO connection {}", config);
        });
    }

    private ClientRegistration build(SsoConfiguration config) {
        return discover(config)
                .registrationId(REGISTRATION_ID)
                .clientId(config.getClientId())
                .clientSecret(config.getClientSecret())
                .clientName(config.getDisplayName())
                .scope(config.scopeSet())
                // matches Spring Security's default callback path
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .build();
    }

    private ClientRegistration.Builder discover(SsoConfiguration config) {
        try {
            return ClientRegistrations.fromIssuerLocation(config.getIssuerUri());
        } catch (RuntimeException e) {
            throw new SsoException("Could not read the OpenID configuration from " + config.getIssuerUri()
                    + ". Check the issuer URL is right and reachable from this server - for Microsoft Entra ID"
                    + " it looks like https://login.microsoftonline.com/<tenant-id>/v2.0.", e);
        }
    }

    /**
     * Who may change this. The route is restricted too; both are here because a
     * page that decides who can authenticate should not rest on one check.
     */
    private static void requireSuperAdmin(UserAccount actor) {
        if (actor == null || !actor.isSuperAdmin()) {
            throw new AccessDeniedException("Only a super admin can change single sign-on");
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** @param updatedAt what the configuration looked like when the registration was built */
    private record CachedRegistration(Long id, java.time.Instant updatedAt, ClientRegistration registration) {

        boolean matches(SsoConfiguration config) {
            return config.getId().equals(id) && config.getUpdatedAt().equals(updatedAt);
        }
    }
}
