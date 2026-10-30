package com.sidbhat.apikeyportal.guided;

/**
 * Providers whose keys can only be created by hand in their own console - there is
 * no API to mint them (GitHub personal access tokens, Anthropic API keys). For these
 * the portal offers a guided walk-through instead: step-by-step instructions, then
 * it verifies the pasted key live against the provider before storing it.
 */
public enum GuidedProvider {

    GITHUB("GitHub", "bi-github", "GitHub", "Personal access token"),
    ANTHROPIC("Anthropic", "bi-robot", "Anthropic", "API key");

    private final String label;

    /** Bootstrap icon class shown next to the provider name. */
    private final String icon;

    /** Used as the service name on keys created through this walk-through. */
    private final String serviceName;

    /** What the provider calls the credential, e.g. in form labels. */
    private final String credentialName;

    GuidedProvider(String label, String icon, String serviceName, String credentialName) {
        this.label = label;
        this.icon = icon;
        this.serviceName = serviceName;
        this.credentialName = credentialName;
    }

    public String getLabel() {
        return label;
    }

    public String getIcon() {
        return icon;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getCredentialName() {
        return credentialName;
    }
}
