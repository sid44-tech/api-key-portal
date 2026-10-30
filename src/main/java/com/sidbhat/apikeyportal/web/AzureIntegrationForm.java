package com.sidbhat.apikeyportal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Backing object for the Azure AD integration setup walk-through.
 */
public class AzureIntegrationForm {

    @NotBlank
    @Size(max = 100)
    private String name = "Azure AD";

    /** Directory (tenant) ID, a GUID, or a verified tenant domain. */
    @NotBlank(message = "must be filled in - step 3 shows where to find it")
    @Size(max = 100)
    private String tenantId;

    /** Application (client) ID of the portal's app registration. */
    @NotBlank(message = "must be filled in - step 3 shows where to find it")
    @Size(max = 100)
    private String clientId;

    @NotBlank(message = "must be filled in - step 5 shows how to create it")
    @Size(max = 1024)
    private String clientSecret;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }
}
