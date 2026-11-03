package io.github.apikeyportal.security;

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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;

import io.github.apikeyportal.logging.AccessDeniedLogger;
import io.github.apikeyportal.logging.AuditLog;
import io.github.apikeyportal.mfa.MfaProperties;
import io.github.apikeyportal.logging.PrincipalLogContextFilter;
import io.github.apikeyportal.sso.PasswordLoginFailureHandler;
import io.github.apikeyportal.sso.SsoAuthenticationFailureHandler;
import io.github.apikeyportal.sso.SsoDisabledRedirectFilter;
import io.github.apikeyportal.sso.SsoService;
import io.github.apikeyportal.sso.SsoUserService;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({SecurityProperties.class, LockoutProperties.class, MfaProperties.class})
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

    /** Optional for the same reason; without it the chain simply does not throttle. */
    private final ObjectProvider<LoginAttemptService> loginAttempts;

    /** Optional likewise; without it refusals still 403, they are just not written down. */
    private final ObjectProvider<AccessDeniedLogger> accessDeniedLogger;

    /** Optional likewise; without it log lines carry no signed-in user. */
    private final ObjectProvider<LoginAuditListener> loginAudit;

    private final ObjectProvider<AuditLog> audit;

    public SecurityConfig(ObjectProvider<SsoUserService> ssoUserService,
            ObjectProvider<SsoAuthenticationFailureHandler> ssoFailureHandler,
            ObjectProvider<SsoService> ssoService,
            ObjectProvider<PasswordLoginFailureHandler> passwordFailureHandler,
            ObjectProvider<LoginAttemptService> loginAttempts,
            ObjectProvider<AccessDeniedLogger> accessDeniedLogger,
            ObjectProvider<LoginAuditListener> loginAudit, ObjectProvider<AuditLog> audit) {
        this.ssoUserService = ssoUserService;
        this.ssoFailureHandler = ssoFailureHandler;
        this.ssoService = ssoService;
        this.passwordFailureHandler = passwordFailureHandler;
        this.loginAttempts = loginAttempts;
        this.accessDeniedLogger = accessDeniedLogger;
        this.loginAudit = loginAudit;
        this.audit = audit;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, MfaProperties mfaProperties) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        // /theme.css is the chosen colours as a stylesheet; the sign-in
                        // page wears them too, and there is nothing in it to protect
                        .requestMatchers("/webjars/**", "/css/**", "/js/**", "/favicon.ico", "/theme.css",
                                "/theme/logo").permitAll()
                        // The login page by path, so every query string reaches it.
                        // formLogin().permitAll() only permits the exact URLs it knows
                        // about ("/login", "/login?error"), so our own "?registered" and
                        // "?passwordChanged" would otherwise be treated as a protected
                        // page: the redirect to sign in gets stashed as the saved request,
                        // and the next successful sign-in lands back on the login page
                        // instead of the portal.
                        .requestMatchers("/login").permitAll()
                        // health checks are for monitors, which have no session. Anonymously
                        // they get a status and an HTTP code; the details need a super admin
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
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
                                "/integrations/google-cloud", "/integrations/aws",
                                "/integrations/gitlab", "/integrations/cloudflare",
                                "/integrations/alerts/**")
                                .hasAnyRole("SUPER_ADMIN", "GROUP_ADMIN")
                        // connecting the organisation's identity provider is the one
                        // thing only the super admin can do: it decides who gets in
                        .requestMatchers("/sso/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/groups/**").hasRole("SUPER_ADMIN")
                        // the colours everybody sees are the super admin's to choose
                        .requestMatchers("/appearance/**").hasRole("SUPER_ADMIN")
                        .anyRequest().authenticated())
                .formLogin(form -> {
                    form.loginPage("/login").permitAll();
                    // a correct password lands on the code page for accounts with a
                    // second factor, and where it always did for the rest
                    form.successHandler(new MfaAuthenticationSuccessHandler());
                    // tells somebody whose account is linked to the provider to use
                    // the button, instead of a flat "invalid email or password"
                    passwordFailureHandler.ifAvailable(form::failureHandler);
                })
                .logout(logout -> {
                    logout.logoutSuccessUrl("/login?logout").permitAll();
                    // signing out is half of "when was this account in use"
                    loginAudit.ifAvailable(logout::addLogoutHandler);
                })
                // a new session id on sign-in, so a pre-login session can't be reused
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                // every refusal written down in one place, rather than in each
                // of the twenty services that can refuse something
                .exceptionHandling(exceptions ->
                        accessDeniedLogger.ifAvailable(exceptions::accessDeniedHandler))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN)))
                // after authorization, so they only ever see requests that were going
                // to be served anyway. The second factor first: it is part of signing
                // in, and the password change comes once the person is fully in
                .addFilterAfter(new MfaRequiredFilter(mfaProperties), AuthorizationFilter.class)
                .addFilterAfter(new PasswordChangeRequiredFilter(), MfaRequiredFilter.class)
                // likewise after authorization, which is the first point at which
                // "who is this" has an answer to put in the logging context
                .addFilterAfter(new PrincipalLogContextFilter(), AuthorizationFilter.class);

        // Turns a submitted sign-in away while that account or address is locked
        // out. Before the authentication filter, so no password is ever checked
        // while a lockout is in force.
        LoginAttemptService attempts = loginAttempts.getIfAvailable();
        AuditLog auditLog = audit.getIfAvailable();
        if (attempts != null && auditLog != null) {
            http.addFilterBefore(new LoginAttemptFilter(attempts, auditLog),
                    UsernamePasswordAuthenticationFilter.class);
        }

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
