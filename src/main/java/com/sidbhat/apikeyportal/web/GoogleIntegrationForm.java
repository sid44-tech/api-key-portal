package com.sidbhat.apikeyportal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Backing object for the Google Cloud integration setup walk-through. The pasted
 * service account JSON key carries everything needed (project ID, client email,
 * private key), so it is the only credential field.
 */
public class GoogleIntegrationForm {

    @NotBlank
    @Size(max = 100)
    private String name = "Google Cloud";

    @NotBlank(message = "must be filled in - step 3 shows how to download it")
    @Size(max = 10000)
    private String serviceAccountJson;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getServiceAccountJson() {
        return serviceAccountJson;
    }

    public void setServiceAccountJson(String serviceAccountJson) {
        this.serviceAccountJson = serviceAccountJson;
    }
}
