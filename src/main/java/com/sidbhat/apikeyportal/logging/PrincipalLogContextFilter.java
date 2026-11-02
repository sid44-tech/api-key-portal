package com.sidbhat.apikeyportal.logging;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds the signed-in user to the logging context, once there is one to add.
 * <p>
 * Separate from {@link AuditContextFilter} because of where each has to sit: the
 * address is wanted before authentication happens, and the user only exists
 * after it. This one goes inside the security chain, after authorisation, which
 * is the first point at which the question "who is this" has an answer - and
 * also the point after which everything interesting happens, since every
 * controller and service runs downstream of it.
 */
public class PrincipalLogContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // "anonymousUser" is not somebody; leaving the field empty says the same
        // thing without looking like an account
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            AuditLogContext.put(AuditLogContext.USER, authentication.getName());
        }
        // no finally: AuditContextFilter clears the whole context further out,
        // and clearing here would lose the user from anything logged on the way
        // back out of the chain
        chain.doFilter(request, response);
    }
}
