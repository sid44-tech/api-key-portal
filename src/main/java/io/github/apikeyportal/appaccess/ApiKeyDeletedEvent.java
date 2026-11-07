package io.github.apikeyportal.appaccess;

/**
 * A key was deleted. Published inside the deleting transaction so that grants
 * on it move to its replacement, or go, along with it.
 *
 * @param successorId the key that replaced it, or null if nothing did
 */
public record ApiKeyDeletedEvent(Long keyId, Long successorId) {
}
