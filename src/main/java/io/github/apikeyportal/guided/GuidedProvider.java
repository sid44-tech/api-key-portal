package io.github.apikeyportal.guided;

/**
 * Providers whose keys can only be created by hand in their own console - there is
 * no API to mint them (GitHub personal access tokens, Anthropic API keys, Atlassian
 * API tokens). For these
 * the portal offers a guided walk-through instead: step-by-step instructions, then
 * it verifies the pasted key live against the provider before storing it.
 */
public enum GuidedProvider {

    GITHUB("GitHub", "GitHub", "Personal access token"),
    ANTHROPIC("Anthropic", "Anthropic", "API key"),
    ATLASSIAN("Atlassian", "Atlassian", "API token"),
    OPENAI("OpenAI", "OpenAI", "API key"),
    STRIPE("Stripe", "Stripe", "API key");

    private final String label;

    /** Used as the service name on keys created through this walk-through. */
    private final String serviceName;

    /** What the provider calls the credential, e.g. in form labels. */
    private final String credentialName;

    GuidedProvider(String label, String serviceName, String credentialName) {
        this.label = label;
        this.serviceName = serviceName;
        this.credentialName = credentialName;
    }

    public String getLabel() {
        return label;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getCredentialName() {
        return credentialName;
    }
}
