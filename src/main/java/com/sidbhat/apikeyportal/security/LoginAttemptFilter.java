package com.sidbhat.apikeyportal.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import com.sidbhat.apikeyportal.logging.AuditLog;

/**
 * Turns a submitted sign-in away while the account or the caller's address is
 * locked out, before any password is checked.
 * <p>
 * Checking here rather than inside the authentication provider is what makes the
 * lockout worth having: no password is compared, so a correct guess landing
 * during a lockout is no better than a wrong one, and the BCrypt work a flood of
 * guesses would otherwise cost the portal is never done. It sits after the CSRF
 * filter, so a locked-out caller still has to present a valid token to get this
 * far - and nothing here touches the session.
 */
public class LoginAttemptFilter extends OncePerRequestFilter {

    /** Where the sign-in form posts, and the field it posts the email in. */
    static final String LOGIN_PATH = "/login";
    private static final String USERNAME_PARAMETER = "username";

    private final LoginAttemptService attempts;
    private final AuditLog audit;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    public LoginAttemptFilter(LoginAttemptService attempts, AuditLog audit) {
        this.attempts = attempts;
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isLoginSubmission(request)) {
            chain.doFilter(request, response);
            return;
        }
        String email = request.getParameter(USERNAME_PARAMETER);
        String address = request.getRemoteAddr();
        if (!attempts.isLockedOut(email, address)) {
            chain.doFilter(request, response);
            return;
        }
        // the email only, never the password that came with it
        audit.refused("sign_in", email, email,
                "Locked out until " + attempts.lockedUntil(email, address));
        redirectStrategy.sendRedirect(request, response, request.getContextPath() + LOGIN_PATH + "?lockedOut");
    }

    private static boolean isLoginSubmission(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && (request.getContextPath() + LOGIN_PATH).equals(request.getRequestURI());
    }
}
