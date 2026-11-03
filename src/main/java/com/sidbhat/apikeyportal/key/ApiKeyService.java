package com.sidbhat.apikeyportal.key;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sidbhat.apikeyportal.activity.ActivityAction;
import com.sidbhat.apikeyportal.activity.ActivityService;
import com.sidbhat.apikeyportal.activity.KeyOrigin;
import com.sidbhat.apikeyportal.activity.RevealMethod;
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
            boolean showExpired, int page) {
        // ordering is applied by the specification, so the PageRequest stays unsorted
        return repository.findAll(Specification.allOf(ApiKeySpecifications.visibleTo(viewer),
                        ApiKeySpecifications.textContains(text),
                        ApiKeySpecifications.serviceIs(serviceName),
                        ApiKeySpecifications.ownedBy(ownerId),
                        ApiKeySpecifications.expiredHidden(showExpired, LocalDate.now()),
                        ApiKeySpecifications.orderBy(sort != null ? sort : ApiKeySort.SERVICE)),
                PageRequest.of(Math.max(page, 0), PAGE_SIZE));
    }

    /**
     * The replacements already minted for any of {@code keys}, by the id of the key
     * they replace. The list page uses this to point an expiring key at its
     * successor without a query per row.
     */
    public Map<Long, ApiKey> findReplacements(List<ApiKey> keys) {
        List<Long> ids = keys.stream().map(ApiKey::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            // Collections.emptyMap, not Map.of: the template asks it about ids
            // that can be null, which Map.of refuses on principle
            return Collections.emptyMap();
        }
        return repository.findByRotatedFromKeyIdIn(ids).stream()
                .collect(Collectors.toMap(ApiKey::getRotatedFromKeyId, replacement -> replacement));
    }

    /** The replacement minted for this key, if any - for the key's own page. */
    public ApiKey findReplacement(Long keyId) {
        return repository.findByRotatedFromKeyId(keyId).orElse(null);
    }

    /**
     * Status counts across the keys {@code viewer} can see. Computed in memory using
     * {@link ExpirationStatus} so there's a single definition of "expiring soon" -
     * fine for the number of keys one group has.
     */
    public KeySummary getSummary(UserAccount viewer) {
        return KeySummary.of(repository.findAll(ApiKeySpecifications.visibleTo(viewer)));
    }

    /**
     * What needs attention among the keys {@code viewer} can see - or, for a
     * super admin, within one group when {@code groupId} is given. Anyone else's
     * {@code groupId} is ignored: their scope is their group, full stop.
     */
    public KeyDashboard dashboard(UserAccount viewer, Long groupId, LocalDate today) {
        Specification<ApiKey> scope = ApiKeySpecifications.visibleTo(viewer);
        if (viewer.isSuperAdmin()) {
            scope = scope.and(ApiKeySpecifications.inGroup(groupId));
        }
        return KeyDashboard.of(repository.findAll(scope), today, Instant.now());
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
     * <p>
     * Recorded twice over, on purpose: "last revealed" on the key answers "is this
     * value still only ever been seen by its owner, and when did they last need
     * it", while the history keeps every reveal separately so an audit can show
     * who took a value out of the portal, when, and whether it went to their
     * clipboard. The second question is the one that matters after a leak, and the
     * date on the key cannot answer it.
     *
     * @throws AccessDeniedException if {@code viewer} doesn't own the key
     */
    @Transactional
    public String revealValue(Long id, UserAccount viewer, RevealMethod method) {
        ApiKey key = requireOwnership(id, viewer, "reveal");
        repository.markRevealed(id, Instant.now());
        activity.recordKeyRevealed(viewer, key.getId(), key.getServiceName(), key.getName(), method);
        log.info("Value of {} revealed by {} ({})", key, viewer.getEmail(), method);
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
        return create(details, owner, origin, KeyProvenance.none());
    }

    /**
     * As {@link #create(ApiKeyDetails, UserAccount, KeyOrigin)}, for keys with a
     * provider behind them: the provenance makes the key rotatable later, and for
     * a rotation carries the link back to the key being replaced.
     */
    @Transactional
    public ApiKey create(ApiKeyDetails details, UserAccount owner, KeyOrigin origin, KeyProvenance provenance) {
        ApiKey newKey = new ApiKey(details.getName().trim(), details.getServiceName().trim(),
                details.getKeyValue().trim(),
                details.getCreatedOn() != null ? details.getCreatedOn() : LocalDate.now(),
                details.getExpiresOn());
        newKey.setNotes(trimToNull(details.getNotes()));
        newKey.assignTo(owner, provenance.group() != null ? provenance.group() : owner.getGroup());
        newKey.recordProvenance(provenance.integrationId(), provenance.providerResourceId(),
                provenance.autoRotate(), provenance.rotatedFromKeyId());
        newKey.setProviderCredentialId(provenance.providerCredentialId());
        ApiKey key = repository.save(newKey);
        activity.recordKeyCreated(owner, key.getId(), key.getServiceName(), key.getName(), origin);
        log.info("Created {} for {} ({})", key, owner.getEmail(), origin.source());
        return key;
    }

    /**
     * Swaps a key's value for a freshly rotated one, keeping everything else.
     * <p>
     * For providers whose rotation revokes the old credential as it issues the
     * new one (GitLab), there is nothing to hand over: the key simply holds a
     * different value from now on. Recorded as an edit of the key, because that
     * is exactly what it is - the owner should see it in the history.
     *
     * @param providerResourceId the provider's new handle for the credential,
     *                           so the next rotation targets the right one
     */
    @Transactional
    public ApiKey replaceValueInPlace(Long id, String newValue, LocalDate expiresOn,
            String providerResourceId) {
        ApiKey key = repository.findById(id).orElseThrow(() -> new ApiKeyNotFoundException(id));
        key.setKeyValue(newValue);
        key.setExpiresOn(expiresOn);
        key.recordProvenance(key.getIntegrationId(), providerResourceId, key.getAutoRotate(),
                key.getRotatedFromKeyId());
        activity.recordKeyUpdated(key.getOwner(), key.getId(), key.getServiceName(), key.getName(),
                "Token rotated automatically; the previous value was revoked by the provider");
        log.info("Rotated the value of {} in place", key);
        return key;
    }

    /**
     * Records that the portal deleted this credential at its provider, which is
     * how an expiry date on a Google or AWS key becomes true rather than a note.
     * The row stays - the history of what the key was, who revealed it and what
     * replaced it is still worth having - but from here on its value is dead.
     * <p>
     * Written in the owner's name, as the rotation that led here was: it happens
     * because they switched automatic replacement on.
     *
     * @param detail what happened at the provider, for the history
     */
    @Transactional
    public ApiKey markRevokedAtProvider(Long id, String detail) {
        ApiKey key = repository.findById(id).orElseThrow(() -> new ApiKeyNotFoundException(id));
        key.markRevoked(Instant.now());
        activity.record(ActivityAction.KEY_REVOKED, key.getOwner(), key.getId(),
                key.getServiceName() + " / " + key.getName(), detail);
        log.info("Deleted {} at its provider: {}", key, detail);
        return key;
    }

    /**
     * The owner's per-key say on automatic replacement: null returns the key to
     * their setting for the provider, true or false pins it. Owner-only for the
     * same reason revealing is - this decides whether new credentials get minted
     * in a provider on their behalf.
     */
    @Transactional
    public ApiKey setAutoRotate(Long id, Boolean autoRotate, UserAccount viewer) {
        ApiKey key = requireOwnership(id, viewer, "change automatic replacement for");
        key.setAutoRotate(autoRotate);
        log.info("{} set automatic replacement for {} to {}", viewer.getEmail(), key,
                autoRotate == null ? "their provider setting" : autoRotate);
        return key;
    }

    /**
     * Applies an edit and records what was changed in the history. Saving a form
     * nobody touched changes nothing and records nothing, so the history stays a
     * list of edits rather than of visits to the edit page.
     */
    @Transactional
    public ApiKey update(Long id, ApiKeyDetails details, UserAccount viewer) {
        ApiKey key = requireOwnership(id, viewer, "edit");
        String name = details.getName().trim();
        String serviceName = details.getServiceName().trim();
        // blank means "keep the current value"
        String keyValue = trimToNull(details.getKeyValue());
        String notes = trimToNull(details.getNotes());

        List<String> changes = new ArrayList<>();
        addIfChanged(changes, "name", key.getName(), name);
        addIfChanged(changes, "service", key.getServiceName(), serviceName);
        if (keyValue != null && !keyValue.equals(key.getKeyValue())) {
            // that it changed, never to what: this goes into an audit record
            changes.add("value");
        }
        addIfChanged(changes, "created date", key.getCreatedOn(), details.getCreatedOn());
        addIfChanged(changes, "expiry date", key.getExpiresOn(), details.getExpiresOn());
        addIfChanged(changes, "notes", key.getNotes(), notes);

        key.setName(name);
        key.setServiceName(serviceName);
        if (keyValue != null) {
            key.setKeyValue(keyValue);
        }
        key.setCreatedOn(details.getCreatedOn());
        key.setExpiresOn(details.getExpiresOn());
        key.setNotes(notes);

        if (!changes.isEmpty()) {
            activity.recordKeyUpdated(viewer, key.getId(), key.getServiceName(), key.getName(),
                    "Changed " + String.join(", ", changes));
            log.info("Updated {}: changed {}", key, String.join(", ", changes));
        }
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

    /** Notes the field by name if the edit actually changed it. */
    private static void addIfChanged(List<String> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.add(field);
        }
    }
}
