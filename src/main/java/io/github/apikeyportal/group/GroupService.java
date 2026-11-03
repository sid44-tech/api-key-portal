package io.github.apikeyportal.group;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.apikeyportal.activity.ActivityAction;
import io.github.apikeyportal.activity.ActivityService;
import io.github.apikeyportal.logging.AuditLog;
import io.github.apikeyportal.integration.ServiceIntegrationRepository;
import io.github.apikeyportal.invite.UserInvitationRepository;
import io.github.apikeyportal.key.ApiKeyRepository;
import io.github.apikeyportal.user.GroupNameAlreadyUsedException;
import io.github.apikeyportal.user.UserAccount;
import io.github.apikeyportal.user.UserAccountRepository;
import io.github.apikeyportal.user.UserGroup;
import io.github.apikeyportal.user.UserGroupRepository;

/**
 * Creating and removing groups, which only a super admin can do.
 * <p>
 * Groups used to appear as a side effect of signing up. Now that accounts arrive
 * by invitation into a group that already exists, somebody has to make them first.
 */
@Service
@Transactional(readOnly = true)
public class GroupService {

    private static final Logger log = LoggerFactory.getLogger(GroupService.class);

    private final UserGroupRepository groups;
    private final UserAccountRepository accounts;
    private final ApiKeyRepository keys;
    private final ServiceIntegrationRepository integrations;
    private final UserInvitationRepository invitations;
    private final ActivityService activity;
    private final AuditLog audit;

    public GroupService(UserGroupRepository groups, UserAccountRepository accounts, ApiKeyRepository keys,
            ServiceIntegrationRepository integrations, UserInvitationRepository invitations,
            ActivityService activity, AuditLog audit) {
        this.groups = groups;
        this.accounts = accounts;
        this.keys = keys;
        this.integrations = integrations;
        this.invitations = invitations;
        this.activity = activity;
        this.audit = audit;
    }

    /** Every group with what it currently holds, for the groups page. */
    public List<GroupUsage> findAllWithUsage() {
        Map<Long, Long> memberCounts = toCounts(accounts.countByGroup());
        Map<Long, Long> keyCounts = toCounts(keys.countByGroup());
        Map<Long, Long> integrationCounts = toCounts(integrations.countByGroup());
        Instant now = Instant.now();
        return groups.findAllByOrderByNameAsc().stream()
                .map(group -> new GroupUsage(group,
                        memberCounts.getOrDefault(group.getId(), 0L),
                        keyCounts.getOrDefault(group.getId(), 0L),
                        integrationCounts.getOrDefault(group.getId(), 0L),
                        invitations.countPendingInGroup(group.getId(), now)))
                .toList();
    }

    /**
     * @throws GroupNameAlreadyUsedException if a group of that name already exists
     */
    @Transactional
    public UserGroup create(String name, UserAccount actor) {
        String trimmed = name.trim();
        if (groups.existsByNameIgnoreCase(trimmed)) {
            throw new GroupNameAlreadyUsedException(trimmed);
        }
        UserGroup group = groups.save(new UserGroup(trimmed));
        activity.record(ActivityAction.GROUP_CREATED, actor, group.getId(), group.getName());
        log.info("{} created {}", actor, group);
        return group;
    }

    /**
     * Removes an empty group. Anything still pointing at it has to be moved out
     * first: deleting a group out from under a key would leave it visible to
     * nobody, which looks exactly like the portal having lost it.
     *
     * @param actor who is deleting it, for the audit trail
     * @throws GroupNotEmptyException if accounts, keys or integrations still belong to it
     */
    @Transactional
    public void delete(Long id, UserAccount actor) {
        UserGroup group = groups.findById(id).orElseThrow(() -> new GroupNotFoundException(id));
        GroupUsage usage = new GroupUsage(group, accounts.countByGroup_Id(id), keys.countByGroup_Id(id),
                integrations.countByGroup_Id(id), invitations.countPendingInGroup(id, Instant.now()));
        if (!usage.isEmpty()) {
            throw new GroupNotEmptyException(usage);
        }
        groups.delete(group);
        // Only the audit log, not the history page: the history belongs to a
        // group, so a GROUP_DELETED row would be filed under a group that no
        // longer exists and only a super admin could ever read it.
        audit.allowed("group_deleted", actor.getEmail(), group.getName(), null);
    }

    /** Turns {@code [groupId, count]} rows into a map, as the repositories return them. */
    private static Map<Long, Long> toCounts(List<Object[]> rows) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }
}
