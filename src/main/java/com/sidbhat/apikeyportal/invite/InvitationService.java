package com.sidbhat.apikeyportal.invite;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import com.sidbhat.apikeyportal.activity.ActivityAction;
import com.sidbhat.apikeyportal.activity.ActivityService;
import com.sidbhat.apikeyportal.user.NewAccountDetails;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountRepository;
import com.sidbhat.apikeyportal.user.UserAccountService;
import com.sidbhat.apikeyportal.user.UserGroup;
import com.sidbhat.apikeyportal.user.UserGroupRepository;
import com.sidbhat.apikeyportal.user.UserRole;

/**
 * Issuing, withdrawing and accepting invitations - the only route to a new
 * account now that sign-up is closed.
 * <p>
 * Who may invite whom is decided here rather than in the controller, because it
 * is the same rule that governs administering an existing account: a super admin
 * can place anyone in any group, a group admin can only add to their own group
 * and can't mint another super admin.
 */
@Service
@Transactional(readOnly = true)
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("d MMMM 'at' h:mm a").withZone(ZoneId.systemDefault());

    private final UserInvitationRepository invitations;
    private final UserAccountRepository accounts;
    private final UserGroupRepository groups;
    private final UserAccountService userService;
    private final InvitationMailer mailer;
    private final InvitationProperties properties;
    private final ActivityService activity;

    public InvitationService(UserInvitationRepository invitations, UserAccountRepository accounts,
            UserGroupRepository groups, UserAccountService userService, InvitationMailer mailer,
            InvitationProperties properties, ActivityService activity) {
        this.invitations = invitations;
        this.accounts = accounts;
        this.groups = groups;
        this.userService = userService;
        this.mailer = mailer;
        this.properties = properties;
        this.activity = activity;
    }

    /**
     * Creates an invitation and emails the link.
     *
     * @param requestBaseUrl the URL the inviting admin reached the portal on, used
     *                       when {@code portal.invitations.base-url} isn't set
     * @throws AccessDeniedException if {@code inviter} may not issue this invitation
     * @throws InvitationException if the address already has an account or a live invitation
     */
    @Transactional
    public IssuedInvitation invite(InviteRequest request, UserAccount inviter, String requestBaseUrl) {
        String email = UserAccount.normalizeEmail(request.email());
        requireMayInvite(inviter, request.role(), request.groupId());
        if (accounts.existsByEmailIgnoreCase(email)) {
            throw new InvitationException(email + " already has an account in the portal.");
        }
        Instant now = Instant.now();
        invitations.findByEmailIgnoreCaseOrderByCreatedAtDesc(email).stream()
                .filter(existing -> existing.isPending(now))
                .findFirst()
                .ifPresent(live -> {
                    // re-inviting before the day is up would leave two working links for one
                    // address; the admin can withdraw the first one if they want a new one now
                    throw new InvitationException(email + " already has an invitation, valid until "
                            + EXPIRY_FORMAT.format(live.getExpiresAt())
                            + ". Withdraw it first, or wait for it to expire.");
                });

        UserGroup group = groups.findById(request.groupId())
                .orElseThrow(() -> new InvitationException("That group no longer exists."));
        String token = InvitationTokens.newToken();
        UserInvitation invitation = invitations.save(new UserInvitation(email, InvitationTokens.hash(token),
                request.role(), group, inviter, now.plus(properties.getValidFor())));

        String link = link(token, requestBaseUrl);
        activity.record(ActivityAction.INVITATION_SENT, inviter, invitation.getId(), email,
                request.role().getLabel() + " of " + group.getName());
        log.info("{} invited {} as {} into {}", inviter, email, request.role(), group);
        return new IssuedInvitation(invitation, link, mailer.send(invitation, link));
    }

    /**
     * Issues a fresh invitation in place of one that is no longer usable, with the
     * same role and group. Only once the original is out of the way: while a link
     * still works, there is nothing to resend.
     */
    @Transactional
    public IssuedInvitation resend(Long id, UserAccount inviter, String requestBaseUrl) {
        UserInvitation previous = getVisible(id, inviter);
        if (previous.isAccepted()) {
            throw new InvitationException(previous.getEmail() + " has already used their invitation.");
        }
        if (previous.isPending(Instant.now())) {
            throw new InvitationException("That invitation is still valid until "
                    + EXPIRY_FORMAT.format(previous.getExpiresAt()) + ", so there's no need to send another.");
        }
        return invite(new InviteRequest(previous.getEmail(), previous.getRole(), previous.getGroupId()),
                inviter, requestBaseUrl);
    }

    /** Withdraws an invitation, so its link stops working immediately. */
    @Transactional
    public UserInvitation revoke(Long id, UserAccount actor) {
        UserInvitation invitation = getVisible(id, actor);
        if (invitation.isAccepted()) {
            throw new InvitationException(invitation.getEmail()
                    + " has already used their invitation. Disable their account instead.");
        }
        invitation.revoke(Instant.now());
        log.info("{} withdrew the invitation for {}", actor, invitation.getEmail());
        return invitation;
    }

    /** Invitations {@code viewer} may see: all of them for a super admin, their group's otherwise. */
    public List<UserInvitation> findVisibleTo(UserAccount viewer) {
        if (viewer.isSuperAdmin()) {
            return invitations.findAllByOrderByCreatedAtDesc();
        }
        if (viewer.getGroupId() == null) {
            return List.of();
        }
        return invitations.findByGroup_IdOrderByCreatedAtDesc(viewer.getGroupId());
    }

    /**
     * The invitation behind a link, if it can still be used.
     *
     * @throws InvalidInvitationException if it is unknown, used, withdrawn or expired
     */
    public UserInvitation requirePending(String token) {
        UserInvitation invitation = invitations.findByTokenHash(InvitationTokens.hash(token))
                .orElseThrow(() -> new InvalidInvitationException(
                        "This invitation link isn't valid. Ask whoever invited you to send a new one."));
        if (!invitation.isPending(Instant.now())) {
            // deliberately the same wording as an unknown token
            throw new InvalidInvitationException(
                    "This invitation link isn't valid. Ask whoever invited you to send a new one.");
        }
        return invitation;
    }

    /**
     * Creates the account at the end of an invitation and marks the invitation used.
     * The role and group come from the invitation, never from the request.
     */
    @Transactional
    public UserAccount accept(String token, String displayName, String password) {
        UserInvitation invitation = requirePending(token);
        UserAccount account = userService.createInvited(new NewAccountDetails(invitation.getEmail(), displayName,
                password, invitation.getRole(), invitation.getGroup()));
        invitation.accept(Instant.now());
        // the actor is the new account itself - they are the one who acted
        activity.record(ActivityAction.ACCOUNT_CREATED, account, account.getId(), account.getDisplayName(),
                "Accepted an invitation from "
                        + (invitation.getInvitedBy() == null ? "an administrator"
                                : invitation.getInvitedBy().getDisplayName()));
        log.info("{} accepted their invitation", account);
        return account;
    }

    /** True if this person is allowed to invite anyone at all. */
    public static boolean canInvite(UserAccount account) {
        return account.getRole() == UserRole.SUPER_ADMIN
                || (account.getRole() == UserRole.GROUP_ADMIN && account.getGroupId() != null);
    }

    /** The groups {@code inviter} may invite into: every group, or just their own. */
    public List<UserGroup> invitableGroups(UserAccount inviter) {
        if (inviter.isSuperAdmin()) {
            return groups.findAllByOrderByNameAsc();
        }
        return inviter.getGroup() == null ? List.of() : List.of(inviter.getGroup());
    }

    /** The roles {@code inviter} may hand out. Only a super admin can create another. */
    public List<UserRole> invitableRoles(UserAccount inviter) {
        return inviter.isSuperAdmin() ? List.of(UserRole.values())
                : List.of(UserRole.GROUP_ADMIN, UserRole.MEMBER);
    }

    private void requireMayInvite(UserAccount inviter, UserRole role, Long groupId) {
        if (!canInvite(inviter)) {
            throw new AccessDeniedException("Only an administrator can invite people");
        }
        if (groupId == null) {
            throw new InvitationException("Pick a group for the new account.");
        }
        if (inviter.isSuperAdmin()) {
            return;
        }
        if (role == UserRole.SUPER_ADMIN) {
            throw new AccessDeniedException("Only a super admin can invite another super admin");
        }
        if (!Objects.equals(groupId, inviter.getGroupId())) {
            throw new AccessDeniedException("You can only invite people into your own group");
        }
    }

    /**
     * An invitation {@code viewer} administers.
     *
     * @throws AccessDeniedException if it belongs to a group they don't administer
     */
    private UserInvitation getVisible(Long id, UserAccount viewer) {
        UserInvitation invitation = invitations.findById(id)
                .orElseThrow(() -> new InvitationNotFoundException(id));
        if (!canInvite(viewer)) {
            throw new AccessDeniedException("Only an administrator can manage invitations");
        }
        if (!viewer.isSuperAdmin() && !Objects.equals(invitation.getGroupId(), viewer.getGroupId())) {
            throw new AccessDeniedException("Not an invitation into your group");
        }
        return invitation;
    }

    private String link(String token, String requestBaseUrl) {
        String base = properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
                ? requestBaseUrl : properties.getBaseUrl();
        return UriComponentsBuilder.fromUriString(base)
                .path("/invite/{token}")
                .buildAndExpand(token)
                .toUriString();
    }
}
