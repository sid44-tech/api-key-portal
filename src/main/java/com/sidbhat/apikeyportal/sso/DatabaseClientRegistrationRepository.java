package com.sidbhat.apikeyportal.sso;

import java.util.Iterator;
import java.util.Optional;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * Serves Spring Security's one client registration out of the database.
 * <p>
 * Spring Boot's own repository is built from {@code application.yml} at startup,
 * which would mean editing a file and restarting to change identity provider.
 * Reading it from the database instead is what lets a super admin set SSO up
 * from inside the portal, and lets it be switched off again immediately.
 */
@Component
public class DatabaseClientRegistrationRepository
        implements ClientRegistrationRepository, Iterable<ClientRegistration> {

    private final SsoService ssoService;

    public DatabaseClientRegistrationRepository(SsoService ssoService) {
        this.ssoService = ssoService;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (!SsoService.REGISTRATION_ID.equals(registrationId)) {
            return null;
        }
        return ssoService.activeRegistration().orElse(null);
    }

    /**
     * Spring Security iterates this to build its default "pick a provider" page.
     * The portal has its own sign-in page, but the contract still has to hold.
     */
    @Override
    public Iterator<ClientRegistration> iterator() {
        return ssoService.activeRegistration().map(Optional::of).orElseGet(Optional::empty)
                .stream().iterator();
    }
}
