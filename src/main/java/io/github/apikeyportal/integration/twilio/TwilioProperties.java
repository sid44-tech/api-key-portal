package io.github.apikeyportal.integration.twilio;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Endpoint for Twilio. Overridable so tests can point at a stub.
 */
@ConfigurationProperties(prefix = "portal.twilio")
public record TwilioProperties(@DefaultValue("https://api.twilio.com") String apiBaseUrl) {
}
