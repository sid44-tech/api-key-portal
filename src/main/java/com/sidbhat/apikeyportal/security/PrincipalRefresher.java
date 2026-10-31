package com.sidbhat.apikeyportal.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;

/**
 * Replaces the {@link PortalUser} held in the session after its account changes.
 * <p>
 * The principal is a snapshot taken at sign-in, so editing your own name or email
 * would otherwise leave the old one showing until you signed in again.
 */
@Component
public class PrincipalRefresher {

    /** The same repository the filter chain uses by default, so the session is written back. */
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public void refresh(UserAccount account, HttpServletRequest request, HttpServletResponse response) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current == null) {
            return;
        }
        PortalUser refreshed = new PortalUser(account);
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken
                .authenticated(refreshed, current.getCredentials(), refreshed.getAuthorities());
        token.setDetails(current.getDetails());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }
}
