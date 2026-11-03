package io.github.apikeyportal.security;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

import io.github.apikeyportal.user.PortalUser;

/**
 * Where a correct password lands. For most accounts, where it always did; for
 * an account with a second factor, on the code page - with the session marked
 * so that {@link MfaRequiredFilter} lets nothing else through until the code
 * has been accepted. The saved request, if any, stays in the session for the
 * code page to honour afterwards.
 */
public class MfaAuthenticationSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    /** Session attribute: a password has been accepted and a code is owed. */
    public static final String PENDING_ATTRIBUTE = "MFA_PENDING";

    /** Session attribute: wrong codes so far in this sign-in. */
    public static final String FAILURES_ATTRIBUTE = "MFA_FAILURES";

    public static final String VERIFY_PATH = "/mfa/verify";

    public MfaAuthenticationSuccessHandler() {
        setDefaultTargetUrl("/keys");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws ServletException, IOException {
        if (authentication.getPrincipal() instanceof PortalUser user && user.isMfaEnabled() && !user.isSsoLinked()) {
            request.getSession().setAttribute(PENDING_ATTRIBUTE, Boolean.TRUE);
            request.getSession().setAttribute(FAILURES_ATTRIBUTE, 0);
            getRedirectStrategy().sendRedirect(request, response, VERIFY_PATH);
            return;
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }

    public static boolean isPending(HttpServletRequest request) {
        return request.getSession(false) != null
                && Boolean.TRUE.equals(request.getSession(false).getAttribute(PENDING_ATTRIBUTE));
    }
}
