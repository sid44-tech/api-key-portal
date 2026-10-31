package com.sidbhat.apikeyportal.user;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

class WithPortalUserSecurityContextFactory implements WithSecurityContextFactory<WithPortalUser> {

    @Override
    public SecurityContext createSecurityContext(WithPortalUser annotation) {
        PortalUser principal = TestUsers.principal(TestUsers.byName(annotation.value()));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, "password",
                principal.getAuthorities()));
        return context;
    }
}
