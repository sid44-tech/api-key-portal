package com.sidbhat.apikeyportal.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserAccountServiceTest {

    @Mock
    private UserAccountRepository accounts;

    @Mock
    private UserGroupRepository groups;

    /** The real encoder, so the hashing the portal actually does is under test. */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private UserAccountService service;

    @BeforeEach
    void setUp() {
        service = new UserAccountService(accounts, groups, passwordEncoder);
    }

    private void stubSave() {
        when(accounts.save(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static NewAccountDetails invited(String email, String name, String password) {
        return new NewAccountDetails(email, name, password, UserRole.MEMBER, TestUsers.platformTeam());
    }

    @Test
    void createInvitedTakesTheRoleAndGroupFromTheInvitation() {
        stubSave();

        UserAccount account = service.createInvited(new NewAccountDetails(" Alex@Example.COM ", "  Alex Carter ",
                "correct-horse-battery", UserRole.GROUP_ADMIN, TestUsers.platformTeam()));

        assertThat(account.getEmail()).isEqualTo("alex@example.com");
        assertThat(account.getDisplayName()).isEqualTo("Alex Carter");
        assertThat(account.getRole()).isEqualTo(UserRole.GROUP_ADMIN);
        assertThat(account.getGroup().getName()).isEqualTo("Platform team");
        assertThat(account.isEnabled()).isTrue();
        // they chose this password themselves, so there is nothing to force
        assertThat(account.isMustChangePassword()).isFalse();
    }

    @Test
    void createInvitedStoresOnlyABcryptHashOfThePassword() {
        stubSave();

        UserAccount account = service.createInvited(invited("alex@example.com", "Alex", "correct-horse-battery"));

        assertThat(account.getPasswordHash()).startsWith("$2").doesNotContain("correct-horse-battery");
        assertThat(passwordEncoder.matches("correct-horse-battery", account.getPasswordHash())).isTrue();
    }

    @Test
    void createInvitedRejectsAnEmailThatIsAlreadyTaken() {
        when(accounts.existsByEmailIgnoreCase("alex@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.createInvited(
                invited("Alex@Example.com", "Alex", "correct-horse-battery")))
                .isInstanceOf(EmailAlreadyUsedException.class);
        verify(accounts, never()).save(any());
    }

    @Test
    void createInvitedRejectsAPasswordThatIsTooShort() {
        assertThatThrownBy(() -> service.createInvited(invited("alex@example.com", "Alex", "short")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(accounts, never()).save(any());
    }

    @Test
    void changePasswordReplacesTheHashWhenTheCurrentPasswordMatches() {
        UserAccount account = TestUsers.alex();
        account.setPasswordHash(passwordEncoder.encode("old-password-1"));
        when(accounts.findById(2L)).thenReturn(Optional.of(account));

        service.changePassword(2L, "old-password-1", "new-password-9");

        assertThat(passwordEncoder.matches("new-password-9", account.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("old-password-1", account.getPasswordHash())).isFalse();
    }

    @Test
    void changePasswordLiftsTheForcedChangeOnTheSeededAdmin() {
        UserAccount account = TestUsers.alex();
        account.setPasswordHash(passwordEncoder.encode("changeme"));
        account.setMustChangePassword(true);
        when(accounts.findById(2L)).thenReturn(Optional.of(account));

        service.changePassword(2L, "changeme", "a-password-of-their-own");

        assertThat(account.isMustChangePassword()).isFalse();
    }

    @Test
    void aFailedPasswordChangeLeavesTheForcedChangeInPlace() {
        UserAccount account = TestUsers.alex();
        account.setPasswordHash(passwordEncoder.encode("changeme"));
        account.setMustChangePassword(true);
        when(accounts.findById(2L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.changePassword(2L, "guessing", "a-password-of-their-own"))
                .isInstanceOf(IncorrectPasswordException.class);
        assertThat(account.isMustChangePassword()).isTrue();
    }

    @Test
    void changePasswordRejectsAWrongCurrentPassword() {
        UserAccount account = TestUsers.alex();
        String original = passwordEncoder.encode("old-password-1");
        account.setPasswordHash(original);
        when(accounts.findById(2L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.changePassword(2L, "guessing", "new-password-9"))
                .isInstanceOf(IncorrectPasswordException.class);
        assertThat(account.getPasswordHash()).isEqualTo(original);
    }

    @Test
    void updateProfileRejectsAnEmailSomeoneElseAlreadyHas() {
        when(accounts.findById(2L)).thenReturn(Optional.of(TestUsers.alex()));
        when(accounts.existsByEmailIgnoreCase("sam@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.updateProfile(2L,
                new ProfileDetails("sam@example.com", "Alex Carter", null)))
                .isInstanceOf(EmailAlreadyUsedException.class);
    }

    @Test
    void updateProfileAllowsKeepingYourOwnEmail() {
        UserAccount account = TestUsers.alex();
        when(accounts.findById(2L)).thenReturn(Optional.of(account));

        service.updateProfile(2L, new ProfileDetails("Alex@Example.com", " Alex C. ", "  Backend engineer "));

        assertThat(account.getEmail()).isEqualTo("alex@example.com");
        assertThat(account.getDisplayName()).isEqualTo("Alex C.");
        assertThat(account.getJobTitle()).isEqualTo("Backend engineer");
        verify(accounts, never()).existsByEmailIgnoreCase(anyString());
    }

    @Test
    void findVisibleToGivesASuperAdminEveryone() {
        when(accounts.findAllByOrderByDisplayNameAsc()).thenReturn(List.of(TestUsers.alex(), TestUsers.outsider()));

        assertThat(service.findVisibleTo(TestUsers.superAdmin())).hasSize(2);
    }

    @Test
    void findVisibleToGivesAMemberOnlyTheirOwnGroup() {
        when(accounts.findByGroup_IdOrderByDisplayNameAsc(2L)).thenReturn(List.of(TestUsers.alex(), TestUsers.sam()));

        assertThat(service.findVisibleTo(TestUsers.sam()))
                .extracting(UserAccount::getEmail)
                .containsExactly("alex@example.com", "sam@example.com");
    }

    @Test
    void getVisibleRefusesSomeoneFromAnotherGroup() {
        when(accounts.findById(2L)).thenReturn(Optional.of(TestUsers.alex()));

        assertThatThrownBy(() -> service.getVisible(2L, TestUsers.outsider()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aGroupAdminCanPromoteSomeoneInTheirOwnGroup() {
        when(accounts.findById(3L)).thenReturn(Optional.of(TestUsers.sam()));
        when(groups.findById(2L)).thenReturn(Optional.of(TestUsers.platformTeam()));

        UserAccount updated = service.administer(3L, TestUsers.alex(), UserRole.GROUP_ADMIN, 2L, true);

        assertThat(updated.getRole()).isEqualTo(UserRole.GROUP_ADMIN);
    }

    @Test
    void aGroupAdminCannotGrantSuperAdmin() {
        when(accounts.findById(3L)).thenReturn(Optional.of(TestUsers.sam()));

        assertThatThrownBy(() -> service.administer(3L, TestUsers.alex(), UserRole.SUPER_ADMIN, 2L, true))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aGroupAdminCannotMoveSomeoneToAnotherGroup() {
        when(accounts.findById(3L)).thenReturn(Optional.of(TestUsers.sam()));

        assertThatThrownBy(() -> service.administer(3L, TestUsers.alex(), UserRole.MEMBER, 99L, true))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anAdminCannotTouchSomeoneInAnotherGroup() {
        when(accounts.findById(3L)).thenReturn(Optional.of(TestUsers.sam()));

        assertThatThrownBy(() -> service.administer(3L, TestUsers.outsider(), UserRole.MEMBER, 2L, true))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anAdminCannotDisableTheirOwnAccount() {
        when(accounts.findById(2L)).thenReturn(Optional.of(TestUsers.alex()));

        assertThatThrownBy(() -> service.administer(2L, TestUsers.alex(), UserRole.GROUP_ADMIN, 2L, false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anAdminCannotDemoteThemselves() {
        when(accounts.findById(2L)).thenReturn(Optional.of(TestUsers.alex()));

        assertThatThrownBy(() -> service.administer(2L, TestUsers.alex(), UserRole.MEMBER, 2L, true))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aGroupAdminCannotDemoteOrLockOutASuperAdminInTheirGroup() {
        UserAccount superAdmin = TestUsers.account(9L, "boss@example.com", "The Boss",
                UserRole.SUPER_ADMIN, TestUsers.platformTeam());
        when(accounts.findById(9L)).thenReturn(Optional.of(superAdmin));

        assertThatThrownBy(() -> service.administer(9L, TestUsers.alex(), UserRole.MEMBER, 2L, false))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(superAdmin.getRole()).isEqualTo(UserRole.SUPER_ADMIN);
        assertThat(superAdmin.isEnabled()).isTrue();
    }

    @Test
    void getCurrentRefusesAnAccountThatHasSinceBeenDisabled() {
        UserAccount account = TestUsers.alex();
        account.setEnabled(false);
        when(accounts.findById(2L)).thenReturn(Optional.of(account));

        // an open session must stop working the moment the account is disabled
        assertThatThrownBy(() -> service.getCurrent(new PortalUser(TestUsers.alex())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aSuperAdminCanMoveSomeoneBetweenGroups() {
        when(accounts.findById(3L)).thenReturn(Optional.of(TestUsers.sam()));
        when(groups.findById(3L)).thenReturn(Optional.of(TestUsers.group(3L, "The Okafors")));

        UserAccount updated = service.administer(3L, TestUsers.superAdmin(), UserRole.MEMBER, 3L, true);

        assertThat(updated.getGroup().getName()).isEqualTo("The Okafors");
    }
}
