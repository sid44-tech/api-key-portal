package com.sidbhat.apikeyportal.integration.azure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Endpoints for Microsoft Entra ID (Azure AD). Overridable so tests can point at a stub.
 */
@ConfigurationProperties(prefix = "portal.azure")
public record AzureAdProperties(
        @DefaultValue("https://login.microsoftonline.com") String loginBaseUrl,
        @DefaultValue("https://graph.microsoft.com") String graphBaseUrl) {
}
