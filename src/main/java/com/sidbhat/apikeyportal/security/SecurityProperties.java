package com.sidbhat.apikeyportal.security;

import javax.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * The portal is single-user, so the credentials live in configuration rather than the database.
 */
@ConstructorBinding
@ConfigurationProperties(prefix = "portal.security")
@Validated
public class SecurityProperties {

    @NotBlank
    private final String username;

    /** BCrypt hash of the password. */
    @NotBlank(message = "must be set, e.g. with the PORTAL_PASSWORD_HASH environment variable")
    private final String passwordHash;

    public SecurityProperties(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }
}
