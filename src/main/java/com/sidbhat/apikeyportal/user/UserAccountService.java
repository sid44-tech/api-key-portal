package com.sidbhat.apikeyportal.user;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserAccountService {

    private static final Logger log = LoggerFactory.getLogger(UserAccountService.class);

    /** Long enough to matter, short enough that people will actually pick one. */
    public static final int MIN_PASSWORD_LENGTH = 10;

    private final UserAccountRepository accounts;
    private final UserGroupRepository groups;
    private final PasswordEncoder passwordEncoder;

    public UserAccountService(UserAccountRepository accounts, UserGroupRepository groups,
            PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.groups = groups;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates the account behind an accepted invitation. There is no other way in:
     * the role and group come from the invite an administrator issued, not from
     * anything the new user typed, so nobody can grant themselves access by
     * filling in a form.
     *
     * @throws EmailAlreadyUsedException if the email is already registered
     */
    @Transactional
    public UserAccount createInvited(NewAccountDetails details) {
        String email = UserAccount.normalizeEmail(details.email());
        if (accounts.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyUsedException(email);
        }
        requireAcceptablePassword(details.password());

        UserAccount account = new UserAccount(email, details.displayName().trim(),
                passwordEncoder.encode(details.password()), details.role(), details.group());
        UserAccount saved = accounts.save(account);
        log.info("Created {} from an invitation", saved);
        return saved;
    }

    /**
     * The account behind the signed-in principal, reloaded so it is never stale.
     * <p>
     * {@code enabled} is re-checked on every request, not just at sign-in: disabling
     * an account has to cut off the session the person is already holding, otherwise
     * revoking access wouldn't actually revoke anything until they signed out.
     *
     * @throws AccessDeniedException if the account has since been disabled
     */
    public UserAccount getCurrent(PortalUser principal) {
        UserAccount account = getById(principal.getId());
        if (!account.isEnabled()) {
            log.warn("Rejected request from {}, whose account is disabled", account);
            throw new AccessDeniedException("This account has been disabled");
        }
        return account;
    }

    public UserAccount getById(Long id) {
        return accounts.findById(id).orElseThrow(() -> new UserAccountNotFoundException(id));
    }

    /**
     * Everyone {@code viewer} is allowed to see: the whole portal for a super admin,
     * otherwise the people in their own group.
     */
    public List<UserAccount> findVisibleTo(UserAccount viewer) {
        if (viewer.isSuperAdmin()) {
            return accounts.findAllByOrderByDisplayNameAsc();
        }
        if (viewer.getGroupId() == null) {
            return List.of(viewer);
        }
        return accounts.findByGroup_IdOrderByDisplayNameAsc(viewer.getGroupId());
    }

    /**
     * A single person, if {@code viewer} is allowed to see them.
     *
     * @throws AccessDeniedException if they are in a different group
     */
    public UserAccount getVisible(Long id, UserAccount viewer) {
        UserAccount account = getById(id);
        if (!viewer.isSuperAdmin() && !viewer.is(account) && !viewer.isInSameGroupAs(account)) {
            throw new AccessDeniedException("Not in your group");
        }
        return account;
    }

    public List<UserGroup> findAllGroups() {
        return groups.findAllByOrderByNameAsc();
    }

    public UserGroup getGroup(Long id) {
        return groups.findById(id).orElseThrow(() -> new IllegalArgumentException("No group " + id));
    }

    @Transactional
    public UserAccount updateProfile(Long id, ProfileDetails details) {
        UserAccount account = getById(id);
        String email = UserAccount.normalizeEmail(details.email());
        if (!email.equals(account.getEmail()) && accounts.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyUsedException(email);
        }
        account.setEmail(email);
        account.setDisplayName(details.displayName().trim());
        account.setJobTitle(trimToNull(details.jobTitle()));
        log.info("Updated profile for {}", account);
        return account;
    }

    /**
     * Replaces the password after checking the current one. Re-checking here rather
     * than trusting the session means a hijacked session still can't take the account over.
     *
     * @throws IncorrectPasswordException if {@code currentPassword} doesn't match
     */
    @Transactional
    public UserAccount changePassword(Long id, String currentPassword, String newPassword) {
        UserAccount account = getById(id);
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            log.warn("Rejected password change for {}: current password did not match", account);
            throw new IncorrectPasswordException();
        }
        requireAcceptablePassword(newPassword);
        account.setPasswordHash(passwordEncoder.encode(newPassword));
        // whatever they were handed is gone now, so the forced-change lock lifts
        account.setMustChangePassword(false);
        log.info("Changed password for {}", account);
        return account;
    }

    /**
     * Changes someone else's role, group or access. Super admins can move anyone
     * anywhere; a group admin can only promote and demote within their own group.
     *
     * @throws AccessDeniedException if {@code actor} may not make this change
     */
    @Transactional
    public UserAccount administer(Long id, UserAccount actor, UserRole role, Long groupId, boolean enabled) {
        UserAccount account = getById(id);
        if (!actor.administers(account)) {
            throw new AccessDeniedException("Not an administrator of this account");
        }
        if (actor.is(account) && (role != account.getRole() || !enabled)) {
            // changing your own role or locking yourself out is how an admin strands a portal
            throw new AccessDeniedException("You cannot change your own role or disable your own account");
        }
        if (!actor.isSuperAdmin()) {
            if (role == UserRole.SUPER_ADMIN) {
                throw new AccessDeniedException("Only a super admin can grant super admin");
            }
            if (!Objects.equals(groupId, account.getGroupId())) {
                throw new AccessDeniedException("Only a super admin can move an account between groups");
            }
        }
        account.setRole(role);
        account.setEnabled(enabled);
        account.setGroup(groupId == null ? null
                : groups.findById(groupId).orElseThrow(() -> new IllegalArgumentException("No group " + groupId)));
        log.info("{} updated {} to role {} in group {}", actor, account, role, groupId);
        return account;
    }

    @Transactional
    public void recordLogin(String email) {
        accounts.findByEmailIgnoreCase(UserAccount.normalizeEmail(email))
                .ifPresent(account -> accounts.recordLogin(account.getId(), Instant.now()));
    }

    private void requireAcceptablePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
