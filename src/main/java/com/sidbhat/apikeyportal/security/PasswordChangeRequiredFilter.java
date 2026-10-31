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

import com.sidbhat.apikeyportal.user.PortalUser;

/**
 * Pins an account that is still on the password it was handed to the
 * change-password page.
 * <p>
 * This is what makes "change it at first sign-in" a rule rather than a
 * suggestion: the seeded super admin's default password is in the README, so
 * leaving the rest of the portal reachable with it would make the whole seeding
 * story a hole. Sits in the filter chain rather than in an interceptor so that
 * it covers every request, including the JSON key-value endpoint.
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    /** The change-password page itself, and the ways out of a session. */
    static final String CHANGE_PASSWORD_PATH = "/profile/password";
    private static final Set<String> ALLOWED = Set.of(CHANGE_PASSWORD_PATH, "/login", "/logout", "/favicon.ico");
    private static final Set<String> ALLOWED_PREFIXES = Set.of("/webjars/", "/css/", "/js/");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (mustChangePassword() && !isAllowed(request)) {
            response.sendRedirect(request.getContextPath() + CHANGE_PASSWORD_PATH);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean mustChangePassword() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getPrincipal() instanceof PortalUser user
                && user.isMustChangePassword();
    }

    private static boolean isAllowed(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return ALLOWED.contains(path) || ALLOWED_PREFIXES.stream().anyMatch(path::startsWith);
    }
}
