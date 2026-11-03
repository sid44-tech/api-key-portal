package io.github.apikeyportal.key;

import io.github.apikeyportal.user.UserGroup;

/**
 * What a newly stored key should carry about where it came from, beyond the
 * fields the owner typed. Passed alongside {@link ApiKeyDetails} on creation so
 * the row is complete the moment it exists, rather than patched afterwards.
 *
 * @param integrationId        the integration that minted it, null for pasted keys
 * @param providerResourceId   the provider resource a replacement would be added to
 * @param providerCredentialId the provider's id for this credential itself, where a
 *                             deletion needs one (Azure's keyId); null otherwise
 * @param autoRotate         a per-key override carried over from the key being
 *                           replaced, null to defer to the owner's settings
 * @param rotatedFromKeyId   the key this one replaces, null unless rotating
 * @param group              which group's list it belongs on; null means the
 *                           owner's current group. A replacement inherits the old
 *                           key's group, because moving a person between groups
 *                           deliberately does not move their keys
 */
public record KeyProvenance(Long integrationId, String providerResourceId, String providerCredentialId,
        Boolean autoRotate, Long rotatedFromKeyId, UserGroup group) {

    /** A key with no provider behind it: pasted in, or from a guided walk-through. */
    public static KeyProvenance none() {
        return new KeyProvenance(null, null, null, null, null, null);
    }

    /** A key minted through an integration, rotatable from now on. */
    public static KeyProvenance provisioned(Long integrationId, String providerResourceId) {
        return provisioned(integrationId, providerResourceId, null);
    }

    /** As above, where the provider also handed back an id for the credential itself. */
    public static KeyProvenance provisioned(Long integrationId, String providerResourceId,
            String providerCredentialId) {
        return new KeyProvenance(integrationId, providerResourceId, providerCredentialId, null, null, null);
    }

    /** A replacement for {@code previous}, hanging off the same provider resource. */
    public static KeyProvenance rotatedFrom(ApiKey previous) {
        return rotatedFrom(previous, null);
    }

    public static KeyProvenance rotatedFrom(ApiKey previous, String providerCredentialId) {
        return new KeyProvenance(previous.getIntegrationId(), previous.getProviderResourceId(),
                providerCredentialId, previous.getAutoRotate(), previous.getId(), previous.getGroup());
    }
}
