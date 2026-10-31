package com.sidbhat.apikeyportal.activity;

/**
 * Where a newly created key came from, as recorded in the history.
 * <p>
 * The detail is free text rather than a reference because this package must not
 * depend on {@code integration} or {@code guided} - those depend on {@code key},
 * which depends on this. It is also the right shape for an audit record: the name
 * of the integration a key was minted through should survive that integration
 * being removed.
 *
 * @param detail the integration or provider it came through, null for a manual entry
 */
public record KeyOrigin(KeySource source, String detail) {

    public static KeyOrigin manual() {
        return new KeyOrigin(KeySource.MANUAL, null);
    }

    /** @param integrationName the shared service integration used to mint the key */
    public static KeyOrigin provisionedBy(String integrationName) {
        return new KeyOrigin(KeySource.PROVISIONED, integrationName);
    }

    /** @param providerLabel the provider whose console the key was created in */
    public static KeyOrigin guidedBy(String providerLabel) {
        return new KeyOrigin(KeySource.GUIDED, providerLabel);
    }
}
