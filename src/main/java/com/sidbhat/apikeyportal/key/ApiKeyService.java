package com.sidbhat.apikeyportal.key;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sidbhat.apikeyportal.activity.ActivityService;
import com.sidbhat.apikeyportal.activity.KeyOrigin;
import com.sidbhat.apikeyportal.user.UserAccount;

/**
 * Reads and writes keys on behalf of a signed-in user.
 * <p>
 * Two different rules apply, and every method takes the viewer so neither can be
 * bypassed by calling the repository directly:
 * <ul>
 *   <li><b>Metadata</b> - name, service, dates, notes - is visible to everyone in
 *       the owner's group, and to a super admin.</li>
 *   <li><b>The value</b>, and editing or deleting the key, is the owner's alone.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private final ApiKeyRepository repository;
    private final ActivityService activity;

    public ApiKeyService(ApiKeyRepository repository, ActivityService activity) {
        this.repository = repository;
        this.activity = activity;
    }

    public static final int PAGE_SIZE = 20;

    /**
     * One page of the keys {@code viewer} can see, matching the filters.
     *
     * @param page zero-based page number
     */
    public Page<ApiKey> search(UserAccount viewer, String text, String serviceName, Long ownerId, ApiKeySort sort,
            int page) {
        // ordering is applied by the specification, so the PageRequest stays unsorted
        return repository.findAll(Specification.allOf(ApiKeySpecifications.visibleTo(viewer),
                        ApiKeySpecifications.textContains(text),
                        ApiKeySpecifications.serviceIs(serviceName),
                        ApiKeySpecifications.ownedBy(ownerId),
                        ApiKeySpecifications.orderBy(sort != null ? sort : ApiKeySort.SERVICE)),
                PageRequest.of(Math.max(page, 0), PAGE_SIZE));
    }

    /**
     * Status counts across the keys {@code viewer} can see. Computed in memory using
     * {@link ExpirationStatus} so there's a single definition of "expiring soon" -
     * fine for the number of keys one group has.
     */
    public KeySummary getSummary(UserAccount viewer) {
        return KeySummary.of(repository.findAll(ApiKeySpecifications.visibleTo(viewer)));
    }

    public List<String> findServiceNames(UserAccount viewer) {
        if (viewer.isSuperAdmin()) {
            return repository.findDistinctServiceNames();
        }
        return viewer.getGroupId() == null ? List.of()
                : repository.findDistinctServiceNamesInGroup(viewer.getGroupId());
    }

    /**
     * A key with its metadata, if {@code viewer} is in a position to see it.
     *
     * @throws AccessDeniedException if the key belongs to another group
     */
    public ApiKey getById(Long id, UserAccount viewer) {
        ApiKey key = repository.findById(id).orElseThrow(() -> new ApiKeyNotFoundException(id));
        if (!canSee(key, viewer)) {
            // 403 rather than 404: the key exists, it just isn't theirs to look at
            throw new AccessDeniedException("Key " + id + " belongs to another group");
        }
        return key;
    }

    /**
     * Returns the decrypted value of a key and records that it was revealed. Only the
     * owner can do this - being a group admin, or even a super admin, is not enough.
     *
     * @throws AccessDeniedException if {@code viewer} doesn't own the key
     */
    @Transactional
    public String revealValue(Long id, UserAccount viewer) {
        ApiKey key = requireOwnership(id, viewer, "reveal");
        repository.markRevealed(id, Instant.now());
        log.info("Value of {} revealed by {}", key, viewer.getEmail());
        return key.getKeyValue();
    }

    /**
     * Stores a new key for {@code owner} and records it in the history.
     * <p>
     * Every route into the portal - the add-key form, a connected integration and
     * the guided walk-throughs - comes through here, so {@code origin} is a
     * required argument rather than an optional extra: a new creation path cannot
     * be added without saying what kind of key it makes.
     */
    @Transactional
    public ApiKey create(ApiKeyDetails details, UserAccount owner, KeyOrigin origin) {
        ApiKey newKey = new ApiKey(details.getName().trim(), details.getServiceName().trim(),
                details.getKeyValue().trim(),
                details.getCreatedOn() != null ? details.getCreatedOn() : LocalDate.now(),
                details.getExpiresOn());
        newKey.setNotes(trimToNull(details.getNotes()));
        newKey.assignTo(owner, owner.getGroup());
        ApiKey key = repository.save(newKey);
        activity.recordKeyCreated(owner, key.getId(), key.getServiceName(), key.getName(), origin);
        log.info("Created {} for {} ({})", key, owner.getEmail(), origin.source());
        return key;
    }

    @Transactional
    public ApiKey update(Long id, ApiKeyDetails details, UserAccount viewer) {
        ApiKey key = requireOwnership(id, viewer, "edit");
        key.setName(details.getName().trim());
        key.setServiceName(details.getServiceName().trim());
        // blank means "keep the current value"
        String keyValue = trimToNull(details.getKeyValue());
        if (keyValue != null) {
            key.setKeyValue(keyValue);
        }
        key.setCreatedOn(details.getCreatedOn());
        key.setExpiresOn(details.getExpiresOn());
        key.setNotes(trimToNull(details.getNotes()));
        log.info("Updated {}", key);
        return key;
    }

    @Transactional
    public ApiKey delete(Long id, UserAccount viewer) {
        ApiKey key = requireOwnership(id, viewer, "delete");
        repository.delete(key);
        activity.recordKeyDeleted(viewer, key.getId(), key.getServiceName(), key.getName());
        log.info("Deleted {}", key);
        return key;
    }

    /**
     * Silences, or restores, the expiry reminder emails for one key. The owner's
     * call alone: they are the one the emails go to.
     */
    @Transactional
    public ApiKey setExpiryNotificationsMuted(Long id, boolean muted, UserAccount viewer) {
        ApiKey key = requireOwnership(id, viewer, "change notifications for");
        key.setExpiryNotificationsMuted(muted);
        log.info("{} {} expiry reminders for {}", viewer.getEmail(), muted ? "muted" : "unmuted", key);
        return key;
    }

    /** Number of keys held by each of the given people, for the people list. */
    public Map<Long, Long> countByOwner(List<Long> ownerIds) {
        if (ownerIds.isEmpty()) {
            return Map.of();
        }
        return repository.countByOwnerIds(ownerIds).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }

    private static boolean canSee(ApiKey key, UserAccount viewer) {
        if (viewer.isSuperAdmin() || key.isOwnedBy(viewer)) {
            return true;
        }
        Long groupId = viewer.getGroupId();
        return groupId != null && key.getGroup() != null && groupId.equals(key.getGroup().getId());
    }

    private ApiKey requireOwnership(Long id, UserAccount viewer, String action) {
        ApiKey key = repository.findById(id).orElseThrow(() -> new ApiKeyNotFoundException(id));
        if (!key.isOwnedBy(viewer)) {
            log.warn("{} tried to {} {}, which they do not own", viewer.getEmail(), action, key);
            throw new AccessDeniedException("Only the owner can " + action + " this key");
        }
        return key;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
