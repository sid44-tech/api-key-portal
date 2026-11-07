package io.github.apikeyportal.appaccess;

import java.time.LocalDate;

/**
 * What the read API returns for one key. {@code value} is null in listings and
 * set only when a single key is read.
 *
 * @param ref   the grant id: the stable reference an app is configured with
 * @param keyId the key currently behind the reference; changes when it is rotated
 */
public record AppKeyRead(Long ref, Long keyId, String name, String service, LocalDate expiresOn, String value) {

    @Override
    public String toString() {
        // never the value
        return "AppKeyRead{ref=" + ref + ", keyId=" + keyId + ", name='" + name + "'}";
    }
}
