package io.github.apikeyportal.activity;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.apikeyportal.logging.AuditLog;
import io.github.apikeyportal.user.UserAccount;
import io.github.apikeyportal.user.UserRole;

/**
 * Records what people do and reads it back for the history page.
 * <p>
 * The recording methods have no transaction of their own: they join whatever
 * transaction the caller is already in, so an event is written if and only if the
 * thing it describes was. A key that failed to save leaves no trace of having been
 * created, and a key that saved always has one.
 */
@Service
@Transactional(readOnly = true)
public class ActivityService {

    private final ActivityEventRepository repository;
    private final AuditLog audit;

    public ActivityService(ActivityEventRepository repository, AuditLog audit) {
        this.repository = repository;
        this.audit = audit;
    }

    /**
     * The history {@code viewer} is allowed to read: everything for a super admin,
     * their own group for a group admin.
     *
     * @throws AccessDeniedException if {@code viewer} is not an administrator
     */
    public Page<ActivityEvent> findVisibleTo(UserAccount viewer, Pageable pageable) {
        requireAdmin(viewer);
        if (viewer.isSuperAdmin()) {
            return repository.findAllByOrderByOccurredAtDesc(pageable);
        }
        if (viewer.getGroupId() == null) {
            return Page.empty(pageable);
        }
        return repository.findByGroupIdOrderByOccurredAtDesc(viewer.getGroupId(), pageable);
    }

    /** True if this person may see any history at all. */
    public static boolean canViewHistory(UserAccount account) {
        return account.getRole() != UserRole.MEMBER;
    }

    @Transactional
    public ActivityEvent recordKeyCreated(UserAccount actor, Long keyId, String serviceName, String keyName,
            KeyOrigin origin) {
        return save(new ActivityEvent(ActivityAction.KEY_CREATED, actor, keyId,
                serviceName + " / " + keyName, origin.source(), origin.detail(), Instant.now()));
    }

    /**
     * Records that somebody read a key's value. The value itself never goes
     * anywhere near this - only that it was asked for, by whom, and how.
     */
    @Transactional
    public ActivityEvent recordKeyRevealed(UserAccount actor, Long keyId, String serviceName, String keyName,
            RevealMethod method) {
        return save(new ActivityEvent(ActivityAction.KEY_REVEALED, actor, keyId,
                serviceName + " / " + keyName, null, method.getLabel(), Instant.now()));
    }

    /**
     * Records an edit.
     *
     * @param changes which fields were changed, in words. Says that the value was
     *                replaced where it was, never what it was replaced with.
     */
    @Transactional
    public ActivityEvent recordKeyUpdated(UserAccount actor, Long keyId, String serviceName, String keyName,
            String changes) {
        return save(new ActivityEvent(ActivityAction.KEY_UPDATED, actor, keyId,
                serviceName + " / " + keyName, null, changes, Instant.now()));
    }

    @Transactional
    public ActivityEvent recordKeyDeleted(UserAccount actor, Long keyId, String serviceName, String keyName) {
        return save(new ActivityEvent(ActivityAction.KEY_DELETED, actor, keyId,
                serviceName + " / " + keyName, null, null, Instant.now()));
    }

    @Transactional
    public ActivityEvent record(ActivityAction action, UserAccount actor, Long targetId, String targetName) {
        return save(new ActivityEvent(action, actor, targetId, targetName, null, null, Instant.now()));
    }

    @Transactional
    public ActivityEvent record(ActivityAction action, UserAccount actor, Long targetId, String targetName,
            String detail) {
        return save(new ActivityEvent(action, actor, targetId, targetName, null, detail, Instant.now()));
    }

    /**
     * Writes the event to the history table and to the audit log.
     * <p>
     * Both, because they answer to different people. The history page is for the
     * group's administrator and lives in the database, where it can be paged
     * through and where deleting a key does not delete the record of it. The log
     * line is for whoever is watching the portal from outside it, and goes
     * wherever the rest of the logs go - somewhere the portal's own
     * administrators cannot quietly edit.
     */
    private ActivityEvent save(ActivityEvent event) {
        ActivityEvent saved = repository.save(event);
        audit.allowed(saved.getAction().name().toLowerCase(java.util.Locale.ROOT), saved.getActorEmail(),
                saved.getTargetName(), saved.getDetail());
        return saved;
    }

    private static void requireAdmin(UserAccount viewer) {
        if (!canViewHistory(viewer)) {
            throw new AccessDeniedException("Only an administrator can see the history");
        }
    }
}
