package io.github.apikeyportal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The values the Cloudflare setup wizard collects: the admin token the portal
 * acts with, and the template token whose permissions every minted token copies.
 */
public class CloudflareIntegrationForm {

    @NotBlank(message = "Give the integration a name")
    @Size(max = 100)
    private String name;

    @NotBlank(message = "Enter the template token's ID")
    @Size(max = 100)
    private String templateTokenId;

    @NotBlank(message = "Enter the API token")
    @Size(max = 256)
    private String apiToken;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTemplateTokenId() {
        return templateTokenId;
    }

    public void setTemplateTokenId(String templateTokenId) {
        this.templateTokenId = templateTokenId == null ? null : templateTokenId.trim();
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken == null ? null : apiToken.trim();
    }

    @Override
    public String toString() {
        // never include the token here, this ends up in logs
        return "CloudflareIntegrationForm{name='" + name + "', templateTokenId='" + templateTokenId + "'}";
    }
}
