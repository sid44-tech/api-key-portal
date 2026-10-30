package com.sidbhat.apikeyportal.integration.google;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Endpoints for Google Cloud. Overridable so tests can point at a stub.
 */
@ConfigurationProperties(prefix = "portal.google")
public record GoogleCloudProperties(
        @DefaultValue("https://oauth2.googleapis.com") String tokenBaseUrl,
        @DefaultValue("https://iam.googleapis.com") String iamBaseUrl) {
}
