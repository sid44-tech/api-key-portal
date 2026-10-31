package com.sidbhat.apikeyportal.activity;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserRole;

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

    private static final Logger log = LoggerFactory.getLogger(ActivityService.class);

    private final ActivityEventRepository repository;

    public ActivityService(ActivityEventRepository repository) {
        this.repository = repository;
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

    private ActivityEvent save(ActivityEvent event) {
        ActivityEvent saved = repository.save(event);
        log.info("Recorded {}", saved);
        return saved;
    }

    private static void requireAdmin(UserAccount viewer) {
        if (!canViewHistory(viewer)) {
            throw new AccessDeniedException("Only an administrator can see the history");
        }
    }
}
