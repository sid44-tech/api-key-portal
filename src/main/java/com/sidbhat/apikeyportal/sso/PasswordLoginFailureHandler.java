package com.sidbhat.apikeyportal.sso;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import com.sidbhat.apikeyportal.user.SsoSignInRequiredException;

/**
 * Keeps the generic "invalid email or password" for password failures, but tells
 * somebody whose account signs in through the provider to use the button.
 * <p>
 * Saying so leaks nothing: it is only reachable by presenting an email that
 * already exists, and the sign-in page is already showing the provider's button
 * to everyone. Leaving it generic would just mean watching people retype a
 * password that is never going to work.
 */
@Component
public class PasswordLoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    public PasswordLoginFailureHandler() {
        super("/login?error");
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        if (exception instanceof SsoSignInRequiredException) {
            getRedirectStrategy().sendRedirect(request, response,
                    request.getContextPath() + "/login?ssoError=" + SsoError.USE_SSO.getCode());
            return;
        }
        super.onAuthenticationFailure(request, response, exception);
    }
}
