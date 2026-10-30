package com.sidbhat.apikeyportal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.sidbhat.apikeyportal.integration.SecretValidity;

/**
 * Backing object for creating a key directly in a connected service provider.
 */
public class ProvisionKeyForm {

    @NotNull
    private Long integrationId;

    /** Used both as the app registration display name in the provider and as the key name here. */
    @NotBlank
    @Size(max = 100)
    private String name;

    @NotNull
    private SecretValidity validity = SecretValidity.DAYS_180;

    /** Capped below the column limit to leave room for the generated client/tenant ID lines. */
    @Size(max = 700)
    private String notes;

    public Long getIntegrationId() {
        return integrationId;
    }

    public void setIntegrationId(Long integrationId) {
        this.integrationId = integrationId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SecretValidity getValidity() {
        return validity;
    }

    public void setValidity(SecretValidity validity) {
        this.validity = validity;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
