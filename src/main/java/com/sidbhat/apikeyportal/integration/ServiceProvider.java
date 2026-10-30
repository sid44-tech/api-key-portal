package com.sidbhat.apikeyportal.integration;

/**
 * Service providers the portal knows how to create credentials in. These are the
 * "connected" providers: a stored integration lets the portal provision keys with
 * a single click. Providers whose keys can only be created by hand in their console
 * (GitHub, Anthropic) are handled by {@link com.sidbhat.apikeyportal.guided.GuidedProvider}
 * instead.
 */
public enum ServiceProvider {

    AZURE_AD("Microsoft Azure AD (Entra ID)", "bi-microsoft"),
    GOOGLE_CLOUD("Google Cloud", "bi-google");

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
