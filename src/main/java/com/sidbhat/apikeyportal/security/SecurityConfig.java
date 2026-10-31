package com.sidbhat.apikeyportal.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;

import com.sidbhat.apikeyportal.sso.PasswordLoginFailureHandler;
import com.sidbhat.apikeyportal.sso.SsoAuthenticationFailureHandler;
import com.sidbhat.apikeyportal.sso.SsoDisabledRedirectFilter;
import com.sidbhat.apikeyportal.sso.SsoService;
import com.sidbhat.apikeyportal.sso.SsoUserService;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    /** No inline scripts or styles; everything is served from this app. */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; "
            + "script-src 'self'; "
            + "style-src 'self'; "
            + "img-src 'self' data:; "
            + "font-src 'self'; "
            + "object-src 'none'; "
            + "base-uri 'self'; "
            + "form-action 'self'; "
            + "frame-ancestors 'none'";

    /**
     * The single sign-on collaborators, if they are on the classpath and wired.
     * Optional so that a sliced web test can exercise this chain without standing
     * up the whole SSO stack; in the running application they are always present.
     */
    private final ObjectProvider<SsoUserService> ssoUserService;
    private final ObjectProvider<SsoAuthenticationFailureHandler> ssoFailureHandler;
    private final ObjectProvider<SsoService> ssoService;
    private final ObjectProvider<PasswordLoginFailureHandler> passwordFailureHandler;

    public SecurityConfig(ObjectProvider<SsoUserService> ssoUserService,
            ObjectProvider<SsoAuthenticationFailureHandler> ssoFailureHandler,
            ObjectProvider<SsoService> ssoService,
            ObjectProvider<PasswordLoginFailureHandler> passwordFailureHandler) {
        this.ssoUserService = ssoUserService;
        this.ssoFailureHandler = ssoFailureHandler;
        this.ssoService = ssoService;
        this.passwordFailureHandler = passwordFailureHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/webjars/**", "/css/**", "/js/**", "/favicon.ico").permitAll()
                        // The login page by path, so every query string reaches it.
                        // formLogin().permitAll() only permits the exact URLs it knows
                        // about ("/login", "/login?error"), so our own "?registered" and
                        // "?passwordChanged" would otherwise be treated as a protected
                        // page: the redirect to sign in gets stashed as the saved request,
                        // and the next successful sign-in lands back on the login page
                        // instead of the portal.
                        .requestMatchers("/login").permitAll()
                        // Error dispatches are forwarded through this chain too. Left
                        // protected, a 403 or 404 for a signed-out visitor becomes their
                        // saved request and sends them to the error page after signing in.
                        .requestMatchers("/error").permitAll()
                        // the only public page: accepting an invitation someone else issued.
                        // The token in the URL is the authorisation; there is no open sign-up.
                        .requestMatchers("/invite/**").permitAll()
                        // issuing invitations and managing groups is for administrators
                        .requestMatchers("/invitations/**").hasAnyRole("SUPER_ADMIN", "GROUP_ADMIN")
                        // the history of who created what, and setting up the shared
                        // integrations everyone in a group then uses
                        .requestMatchers("/activity/**").hasAnyRole("SUPER_ADMIN", "GROUP_ADMIN")
                        .requestMatchers("/integrations/new", "/integrations/azure-ad",
                                "/integrations/google-cloud").hasAnyRole("SUPER_ADMIN", "GROUP_ADMIN")
                        // connecting the organisation's identity provider is the one
                        // thing only the super admin can do: it decides who gets in
                        .requestMatchers("/sso/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/groups/**").hasRole("SUPER_ADMIN")
                        .anyRequest().authenticated())
                .formLogin(form -> {
                    form.loginPage("/login").defaultSuccessUrl("/keys").permitAll();
                    // tells somebody whose account is linked to the provider to use
                    // the button, instead of a flat "invalid email or password"
                    passwordFailureHandler.ifAvailable(form::failureHandler);
                })
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .permitAll())
                // a new session id on sign-in, so a pre-login session can't be reused
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN)))
                // after authorization, so it only ever sees requests that were going
                // to be served anyway
                .addFilterAfter(new PasswordChangeRequiredFilter(), AuthorizationFilter.class);

        // Single sign-on, when a super admin has connected a provider. The client
        // registration is read from the database, so this stays wired up whether or
        // not one is configured; with none, the authorization endpoint has nothing
        // to offer and the sign-in page shows no button.
        SsoUserService oidcUserService = ssoUserService.getIfAvailable();
        SsoAuthenticationFailureHandler failureHandler = ssoFailureHandler.getIfAvailable();
        SsoService sso = ssoService.getIfAvailable();
        if (oidcUserService != null && failureHandler != null && sso != null) {
            http.oauth2Login(oauth2 -> oauth2
                    .loginPage("/login")
                    .defaultSuccessUrl("/keys")
                    .failureHandler(failureHandler)
                    .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService)))
                    // ahead of the authorization endpoint, which throws rather than
                    // explains itself when no provider is connected
                    .addFilterBefore(new SsoDisabledRedirectFilter(sso),
                            OAuth2AuthorizationRequestRedirectFilter.class);
        }
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
