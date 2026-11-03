package com.sidbhat.apikeyportal.security;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.sidbhat.apikeyportal.mfa.MfaProperties;
import com.sidbhat.apikeyportal.user.PortalUser;

/**
 * The second-factor gate. Two jobs, in the same place for the same reason as
 * {@link PasswordChangeRequiredFilter}: a rule that sits in the filter chain
 * covers every request, the JSON value endpoint included.
 * <ul>
 *   <li>While a password has been accepted but no code has, nothing but the
 *       code page and the way out is served. The session is technically
 *       authenticated at that point - Spring's doing - so this is what makes
 *       "pending" mean pending.</li>
 *   <li>Where two-factor is required for everyone, an account without one is
 *       pinned to the enrolment page, after any forced password change.</li>
 * </ul>
 */
public class MfaRequiredFilter extends OncePerRequestFilter {

    static final String SETUP_PATH = "/mfa/setup";
    private static final Set<String> ALWAYS = Set.of("/login", "/logout", "/favicon.ico", "/error");
    private static final Set<String> ALWAYS_PREFIXES = Set.of("/webjars/", "/css/", "/js/", "/theme", "/actuator/health");

    private final MfaProperties properties;

    public MfaRequiredFilter(MfaProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (isAlwaysAllowed(path)) {
            chain.doFilter(request, response);
            return;
        }
        PortalUser user = currentUser();
        if (user != null && MfaAuthenticationSuccessHandler.isPending(request)) {
            if (!path.equals(MfaAuthenticationSuccessHandler.VERIFY_PATH)) {
                response.sendRedirect(request.getContextPath() + MfaAuthenticationSuccessHandler.VERIFY_PATH);
                return;
            }
        } else if (user != null && mustEnrol(user) && !path.startsWith(SETUP_PATH)
                && !path.equals(PasswordChangeRequiredFilter.CHANGE_PASSWORD_PATH)) {
            response.sendRedirect(request.getContextPath() + SETUP_PATH);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Required for everyone, not yet enrolled, signs in with a password - and the password is their own. */
    private boolean mustEnrol(PortalUser user) {
        return properties.required() && !user.isMfaEnabled() && !user.isSsoLinked() && !user.isMustChangePassword();
    }

    private static PortalUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof PortalUser user ? user : null;
    }

    private static boolean isAlwaysAllowed(String path) {
        return ALWAYS.contains(path) || ALWAYS_PREFIXES.stream().anyMatch(path::startsWith);
    }
}
