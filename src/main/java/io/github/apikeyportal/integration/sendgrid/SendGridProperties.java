package io.github.apikeyportal.integration.sendgrid;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Endpoint for SendGrid. Overridable so tests can point at a stub - or at
 * {@code https://api.eu.sendgrid.com} for an EU-pinned account.
 */
@ConfigurationProperties(prefix = "portal.sendgrid")
public record SendGridProperties(@DefaultValue("https://api.sendgrid.com") String apiBaseUrl) {
}
