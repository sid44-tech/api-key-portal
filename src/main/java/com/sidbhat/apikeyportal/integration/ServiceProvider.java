package com.sidbhat.apikeyportal.integration;

/**
 * Service providers the portal knows how to create credentials in.
 */
public enum ServiceProvider {

    AZURE_AD("Microsoft Azure AD (Entra ID)", "bi-microsoft");

    private final String label;

    /** Bootstrap icon class shown next to the provider name. */
    private final String icon;

    ServiceProvider(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    public String getLabel() {
        return label;
    }

    public String getIcon() {
        return icon;
    }
}
