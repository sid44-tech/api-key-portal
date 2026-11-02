package com.sidbhat.apikeyportal.integration;

/**
 * Service providers the portal knows how to create credentials in. These are the
 * "connected" providers: a stored integration lets the portal provision keys with
 * a single click. Providers whose keys can only be created by hand in their console
 * (GitHub, Anthropic) are handled by {@link com.sidbhat.apikeyportal.guided.GuidedProvider}
 * instead.
 */
public enum ServiceProvider {

    AZURE_AD("Microsoft Azure AD (Entra ID)", "bi-microsoft", true, true, true),
    /**
     * No rotation yet: Google service account keys never expire on their own, so
     * replacing one means deleting the old key at the provider too - a bigger
     * step than adding a secret, and one to take deliberately later.
     */
    GOOGLE_CLOUD("Google Cloud", "bi-google", false, false, false),

    /**
     * No rotation for the same reason as Google: IAM access keys never expire on
     * their own, so honest rotation means deleting the old key at AWS too.
     */
    AWS_IAM("Amazon Web Services (IAM)", "bi-amazon", false, false, false),

    /**
     * Rotates through GitLab's own rotate endpoint, which issues a new value and
     * revokes the old one in the same call. No overlap: the key is replaced in
     * place, as late as the portal safely can.
     */
    GITLAB("GitLab", "bi-gitlab", true, true, false),

    CLOUDFLARE("Cloudflare", "bi-cloud-fill", false, true, false);

    private final String label;

    /** Bootstrap icon class shown next to the provider name. */
    private final String icon;

    /** Whether the portal can mint a fresh secret on an existing client here. */
    private final boolean supportsRotation;

    /**
     * Whether a provisioned credential's lifetime is the user's to choose. False
     * where the provider's keys simply never expire (Google, AWS).
     */
    private final boolean supportsValidityChoice;

    /**
     * Whether the credential being replaced keeps working afterwards. Azure adds
     * a second secret to the same client, so both are live until the old one
     * expires; GitLab's rotate revokes the old token the instant it issues the
     * new one. The difference decides whether the portal can hand over gently or
     * has to swap in place - and how early it dares rotate.
     */
    private final boolean rotationOverlaps;

    ServiceProvider(String label, String icon, boolean supportsRotation, boolean supportsValidityChoice,
            boolean rotationOverlaps) {
        this.label = label;
        this.icon = icon;
        this.supportsRotation = supportsRotation;
        this.supportsValidityChoice = supportsValidityChoice;
        this.rotationOverlaps = rotationOverlaps;
    }

    public boolean rotationOverlaps() {
        return rotationOverlaps;
    }

    public boolean supportsRotation() {
        return supportsRotation;
    }

    public boolean supportsValidityChoice() {
        return supportsValidityChoice;
    }

    public String getLabel() {
        return label;
    }

    public String getIcon() {
        return icon;
    }
}
