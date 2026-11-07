package io.github.apikeyportal.appaccess;

import java.time.Instant;
import java.util.List;

/**
 * A token as the App access page shows it. Deliberately carries no hash and no
 * key value - what a member sees is everything here, and nothing in it is secret.
 *
 * @param canManage whether the viewer may delete it
 */
public record AppTokenView(Long id, String name, String groupName, String tokenPrefix, String createdByName,
        Instant createdAt, Instant expiresAt, boolean expired, Instant lastUsedAt, List<Grant> grants,
        boolean canManage) {

    /**
     * @param ref      the reference an app reads it by
     * @param keyLabel the key as it is now, after any rotations
     * @param readable false where the key is gone, deleted at its provider or moved out of the group
     */
    public record Grant(Long ref, String keyLabel, boolean readable, Instant lastReadAt) {
    }
}
