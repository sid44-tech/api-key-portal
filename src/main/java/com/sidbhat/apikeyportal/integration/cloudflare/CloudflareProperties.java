package com.sidbhat.apikeyportal.integration.cloudflare;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Endpoint for Cloudflare. Overridable so tests can point at a stub.
 */
@ConfigurationProperties(prefix = "portal.cloudflare")
public record CloudflareProperties(
        @DefaultValue("https://api.cloudflare.com/client/v4") String apiBaseUrl) {
}
