package com.sidbhat.apikeyportal.invite;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How invitations are addressed and how long they last.
 */
@ConfigurationProperties(prefix = "portal.invitations")
@Validated
public class InvitationProperties {

    /** How long a link works for. A day by default. */
    @NotNull
    private final Duration validFor;

    /** From address on the invitation email. */
    @NotBlank
    private final String fromAddress;

    /**
     * Base URL the links point at, e.g. {@code https://keys.example.com}. Left
     * blank, the portal uses whatever address the inviting admin reached it on,
     * which is right for a single-host deployment and wrong behind a proxy that
     * doesn't forward the original host.
     */
    private final String baseUrl;

    public InvitationProperties(Duration validFor, String fromAddress, String baseUrl) {
        this.validFor = validFor;
        this.fromAddress = fromAddress;
        this.baseUrl = baseUrl;
    }

    public Duration getValidFor() {
        return validFor;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
