package io.github.apikeyportal.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

import io.github.apikeyportal.logging.AuditLog;
import io.github.apikeyportal.user.PortalUser;
import io.github.apikeyportal.user.UserAccountService;

/**
 * The record of who was in the portal and when: every sign-in, every sign-out,
 * and every attempt that didn't work.
 * <p>
 * Also keeps {@link LoginAttemptService}'s count of failed sign-ins, and the
 * "last signed in" the profile and people pages show. All three want the same
 * events, and splitting them across three listeners would mean three places to
 * check when one of them turns out to be missing something.
 */
@Component
public class LoginAuditListener implements LogoutHandler {

    private final UserAccountService users;
    private final LoginAttemptService attempts;
    private final AuditLog audit;

    public LoginAuditListener(UserAccountService users, LoginAttemptService attempts, AuditLog audit) {
        this.users = users;
        this.attempts = attempts;
        this.audit = audit;
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        String email = event.getAuthentication().getName();
        if (event.getAuthentication().getPrincipal() instanceof PortalUser user && user.isMfaEnabled()
                && !user.isSsoLinked()) {
            // the password is half of it: "signed in" and the lockout reset wait
            // for the code, which MfaController records when it comes
            audit.allowed("sign_in", email, email, "Password accepted; second factor pending");
            return;
        }
        users.recordLogin(email);
        attempts.recordSuccess(email, addressOf(event.getAuthentication()));
        audit.allowed("sign_in", email, email, methodOf(event.getAuthentication()));
    }

    /** The second half: a code was accepted, the sign-in is complete. */
    public void onMfaCompleted(String email, String address, String how) {
        users.recordLogin(email);
        attempts.recordSuccess(email, address);
        audit.allowed("sign_in", email, email, "Second factor verified (" + how + ")");
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        String email = event.getAuthentication().getName();
        // the attempted name and why, never the password that was tried
        audit.refused("sign_in", email, email, event.getException().getClass().getSimpleName());
        if (isWrongPassword(event)) {
            attempts.recordFailure(email, addressOf(event.getAuthentication()));
        }
    }

    /**
     * Signing out, including the automatic one when a session is invalidated.
     * Without it the trail shows people arriving and never leaving, and a session
     * that stayed open all night looks the same as one that was closed at five.
     */
    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (authentication != null) {
            audit.allowed("sign_out", authentication.getName(), authentication.getName(), null);
        }
    }

    /**
     * Only a password that didn't work counts towards a lockout. An unknown email
     * arrives here as one too, which is the point - otherwise guessing addresses
     * would be free. The refusals that are not guesses (an account that signs in
     * through the identity provider, a disabled account) are left alone: nobody
     * learns anything from repeating them, and counting them would turn a
     * forgetful colleague into a locked-out one. All of them are still logged.
     */
    private static boolean isWrongPassword(AbstractAuthenticationFailureEvent event) {
        return event.getException() instanceof BadCredentialsException;
    }

    /** Which door they came in by, since the portal has two. */
    private static String methodOf(Authentication authentication) {
        return authentication.getDetails() instanceof WebAuthenticationDetails ? "password" : "sso";
    }

    /** The caller's address, where the authentication carries one. */
    private static String addressOf(Authentication authentication) {
        return authentication.getDetails() instanceof WebAuthenticationDetails details
                ? details.getRemoteAddress()
                : null;
    }
}
