package com.sidbhat.apikeyportal.integration;

/**
 * Service providers the portal knows how to create credentials in. These are the
 * "connected" providers: a stored integration lets the portal provision keys with
 * a single click. Providers whose keys can only be created by hand in their console
 * (GitHub, Anthropic) are handled by {@link com.sidbhat.apikeyportal.guided.GuidedProvider}
 * instead.
 */
public enum ServiceProvider {

    AZURE_AD("Microsoft Azure AD (Entra ID)", "bi-microsoft", true, true, true, false, true),

    /**
     * Google service account keys never expire on their own, so the expiry is the
     * portal's: chosen at creation, and made true by the portal deleting the old
     * key at Google once its replacement has been minted and the date arrives.
     * The replacement is a second key on the same service account, so both work
     * through the hand-over.
     */
    GOOGLE_CLOUD("Google Cloud", "bi-google", true, true, true, true, true),

    /**
     * As Google: IAM access keys never expire on their own, so the portal enforces
     * the date by deleting the old key at AWS after the hand-over. The replacement
     * is a second access key on the same IAM user - AWS allows exactly two, which
     * is what makes an overlap possible at all.
     */
    AWS_IAM("Amazon Web Services (IAM)", "bi-amazon", true, true, true, true, true),

    /**
     * Rotates through GitLab's own rotate endpoint, which issues a new value and
     * revokes the old one in the same call. No overlap: the key is replaced in
     * place, as late as the portal safely can.
     */
    GITLAB("GitLab", "bi-gitlab", true, true, false, false, false),

    CLOUDFLARE("Cloudflare", "bi-cloud-fill", false, true, false, false, false);

    private final String label;

    /** Bootstrap icon class shown next to the provider name. */
    private final String icon;

    /** Whether the portal can mint a fresh secret on an existing client here. */
    private final boolean supportsRotation;

    /**
     * Whether a provisioned credential's lifetime is the user's to choose. True
     * everywhere now: where the provider's keys never expire by themselves
     * (Google, AWS), the choice is the date the portal will enforce instead.
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

    /**
     * Whether the provider never retires a credential itself, so the expiry date
     * is the portal's own - chosen at creation - and only the portal deleting the
     * credential makes it true. Without that an "expired" Google or AWS key is
     * bookkeeping: shown as expired here, working as ever there.
     */
    private final boolean revokedByPortal;

    /**
     * Whether the portal can delete a replaced, expired credential at the
     * provider at all. True wherever a second credential sits beside the old
     * one: Azure's password credentials, Google's keys, AWS's access keys.
     * False for GitLab, which replaces in place and leaves nothing behind, and
     * Cloudflare, which does not rotate. Whether it <em>does</em> is the
     * integration's switch.
     */
    private final boolean canDeleteReplaced;

    ServiceProvider(String label, String icon, boolean supportsRotation, boolean supportsValidityChoice,
            boolean rotationOverlaps, boolean revokedByPortal, boolean canDeleteReplaced) {
        this.label = label;
        this.icon = icon;
        this.supportsRotation = supportsRotation;
        this.supportsValidityChoice = supportsValidityChoice;
        this.rotationOverlaps = rotationOverlaps;
        this.revokedByPortal = revokedByPortal;
        this.canDeleteReplaced = canDeleteReplaced;
    }

    public boolean canDeleteReplaced() {
        return canDeleteReplaced;
    }

    public boolean rotationOverlaps() {
        return rotationOverlaps;
    }

    public boolean revokedByPortal() {
        return revokedByPortal;
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
