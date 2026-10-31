package com.sidbhat.apikeyportal.sso;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns "SSO isn't switched on" into a message on the sign-in page.
 * <p>
 * Spring Security's authorization endpoint throws when it is asked for a client
 * registration that does not exist, which surfaces as a 500. That is reachable
 * without anything being wrong: a bookmarked sign-in link, or a tab left open
 * while a super admin switched the connection off.
 */
public class SsoDisabledRedirectFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_PATH = "/oauth2/authorization/";
    private static final String CALLBACK_PATH = "/login/oauth2/code/";

    private final SsoService ssoService;

    public SsoDisabledRedirectFilter(SsoService ssoService) {
        this.ssoService = ssoService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isSsoEndpoint(request) && !ssoService.isEnabled()) {
            response.sendRedirect(request.getContextPath() + "/login?ssoError="
                    + SsoError.NOT_ENABLED.getCode());
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isSsoEndpoint(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith(AUTHORIZATION_PATH) || path.startsWith(CALLBACK_PATH);
    }
}
