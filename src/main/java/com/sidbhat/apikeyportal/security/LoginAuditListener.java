package com.sidbhat.apikeyportal.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

import com.sidbhat.apikeyportal.user.UserAccountService;

/**
 * Records when each account last signed in, which the profile and people pages show.
 */
@Component
public class LoginAuditListener {

    private static final Logger log = LoggerFactory.getLogger(LoginAuditListener.class);

    private final UserAccountService users;

    public LoginAuditListener(UserAccountService users) {
        this.users = users;
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        String email = event.getAuthentication().getName();
        users.recordLogin(email);
        log.info("Signed in: {}", email);
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        // the attempted name only, never the password that was tried
        log.warn("Failed sign-in attempt for '{}': {}", event.getAuthentication().getName(),
                event.getException().getClass().getSimpleName());
    }
}
