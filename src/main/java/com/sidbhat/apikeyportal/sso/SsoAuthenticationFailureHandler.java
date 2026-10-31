package com.sidbhat.apikeyportal.sso;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Sends a refused SSO sign-in back to the portal's own sign-in page with a
 * reason, rather than Spring Security's generic {@code ?error}.
 * <p>
 * The reason matters here in a way it does not for a wrong password: somebody
 * refused for not being in the access group has authenticated successfully and
 * needs to be told to ask IT, not left guessing at their credentials. Only the
 * fixed codes from {@link SsoError} are echoed, so nothing from the provider's
 * response reaches the URL.
 */
@Component
public class SsoAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(SsoAuthenticationFailureHandler.class);

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        SsoError error = SsoError.FAILED;
        if (exception instanceof OAuth2AuthenticationException oauth2Exception) {
            error = SsoError.fromCode(oauth2Exception.getError().getErrorCode());
        }
        log.warn("SSO sign-in refused: {}", error.name(), exception);
        // redirect directly rather than through setDefaultFailureUrl: this is a
        // singleton, and two refusals at once would otherwise swap each other's
        // messages
        getRedirectStrategy().sendRedirect(request, response,
                request.getContextPath() + "/login?ssoError=" + error.getCode());
    }
}
