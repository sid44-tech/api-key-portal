package com.sidbhat.apikeyportal.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import com.sidbhat.apikeyportal.activity.ActivityService;
import com.sidbhat.apikeyportal.user.NewAccountDetails;
import com.sidbhat.apikeyportal.user.TestUsers;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountRepository;
import com.sidbhat.apikeyportal.user.UserAccountService;
import com.sidbhat.apikeyportal.user.UserGroup;
import com.sidbhat.apikeyportal.user.UserGroupRepository;
import com.sidbhat.apikeyportal.user.UserRole;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvitationServiceTest {

    private static final String BASE_URL = "https://keys.example.com";

    @Mock
    private UserInvitationRepository invitations;

    @Mock
    private UserAccountRepository accounts;

    @Mock
    private UserGroupRepository groups;

    @Mock
    private UserAccountService userService;

    @Mock
    private InvitationMailer mailer;

    @Mock
    private ActivityService activity;

    private InvitationService service;

    private final UserGroup platformTeam = TestUsers.platformTeam();
    private final UserGroup okafors = TestUsers.group(3L, "The Okafors");

    @BeforeEach
    void setUp() {
        service = new InvitationService(invitations, accounts, groups, userService, mailer,
                new InvitationProperties(Duration.ofDays(1), "no-reply@example.com", BASE_URL), activity);
        when(groups.findById(2L)).thenReturn(Optional.of(platformTeam));
        when(groups.findById(3L)).thenReturn(Optional.of(okafors));
        when(invitations.save(any(UserInvitation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(invitations.findByEmailIgnoreCaseOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
        when(mailer.send(any(), anyString())).thenReturn(true);
    }

    private static UserInvitation invitation(long id, String email, UserGroup group, Instant expiresAt) {
        UserInvitation invitation = new UserInvitation(email, InvitationTokens.hash("a-token"), UserRole.MEMBER,
                group, TestUsers.alex(), expiresAt);
        ReflectionTestUtils.setField(invitation, "id", id);
        return invitation;
    }

    private UserInvitation issued() {
        ArgumentCaptor<UserInvitation> saved = ArgumentCaptor.forClass(UserInvitation.class);
        verify(invitations).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void aGroupAdminInvitesIntoTheirOwnGroup() {
        IssuedInvitation result = service.invite(
                new InviteRequest(" Sam@Example.COM ", UserRole.MEMBER, 2L), TestUsers.alex(), BASE_URL);

        UserInvitation invitation = issued();
        assertThat(invitation.getEmail()).isEqualTo("sam@example.com");
        assertThat(invitation.getRole()).isEqualTo(UserRole.MEMBER);
        assertThat(invitation.getGroup()).isEqualTo(platformTeam);
        assertThat(invitation.getInvitedBy().getEmail()).isEqualTo("alex@example.com");
        assertThat(result.emailed()).isTrue();
    }

    @Test
    void theInvitationLastsExactlyOneDay() {
        Instant before = Instant.now();

        service.invite(new InviteRequest("sam@example.com", UserRole.MEMBER, 2L), TestUsers.alex(), BASE_URL);

        assertThat(issued().getExpiresAt())
                .isBetween(before.plus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS));
    }

    @Test
    void onlyTheHashOfTheTokenIsStoredAndTheLinkCarriesTheTokenItself() {
        IssuedInvitation result = service.invite(
                new InviteRequest("sam@example.com", UserRole.MEMBER, 2L), TestUsers.alex(), BASE_URL);

        String token = result.link().substring(result.link().lastIndexOf('/') + 1);
        assertThat(result.link()).startsWith(BASE_URL + "/invite/");
        assertThat(issued().getTokenHash())
                .isEqualTo(InvitationTokens.hash(token))
                .doesNotContain(token);
    }

    @Test
    void aSuperAdminInvitesIntoAnyGroup() {
        service.invite(new InviteRequest("sam@example.com", UserRole.GROUP_ADMIN, 3L),
                TestUsers.superAdmin(), BASE_URL);

        assertThat(issued().getGroup()).isEqualTo(okafors);
    }

    @Test
    void aGroupAdminCannotInviteIntoSomeoneElsesGroup() {
        assertThatThrownBy(() -> service.invite(new InviteRequest("sam@example.com", UserRole.MEMBER, 3L),
                TestUsers.alex(), BASE_URL))
                .isInstanceOf(AccessDeniedException.class);
        verify(invitations, never()).save(any());
    }

    @Test
    void aGroupAdminCannotInviteASuperAdmin() {
        assertThatThrownBy(() -> service.invite(new InviteRequest("sam@example.com", UserRole.SUPER_ADMIN, 2L),
                TestUsers.alex(), BASE_URL))
                .isInstanceOf(AccessDeniedException.class);
        verify(invitations, never()).save(any());
    }

    @Test
    void aPlainMemberCannotInviteAnyone() {
        assertThatThrownBy(() -> service.invite(new InviteRequest("new@example.com", UserRole.MEMBER, 2L),
                TestUsers.sam(), BASE_URL))
                .isInstanceOf(AccessDeniedException.class);
        verify(invitations, never()).save(any());
    }

    @Test
    void refusesToInviteSomeoneWhoAlreadyHasAnAccount() {
        when(accounts.existsByEmailIgnoreCase("sam@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.invite(new InviteRequest("sam@example.com", UserRole.MEMBER, 2L),
                TestUsers.alex(), BASE_URL))
                .isInstanceOf(InvitationException.class)
                .hasMessageContaining("already has an account");
        verify(invitations, never()).save(any());
    }

    @Test
    void refusesASecondInvitationWhileTheFirstStillWorks() {
        when(invitations.findByEmailIgnoreCaseOrderByCreatedAtDesc("sam@example.com"))
                .thenReturn(List.of(invitation(1L, "sam@example.com", platformTeam,
                        Instant.now().plus(6, ChronoUnit.HOURS))));

        assertThatThrownBy(() -> service.invite(new InviteRequest("sam@example.com", UserRole.MEMBER, 2L),
                TestUsers.alex(), BASE_URL))
                .isInstanceOf(InvitationException.class)
                .hasMessageContaining("already has an invitation");
        verify(invitations, never()).save(any());
    }

    @Test
    void allowsAFreshInvitationOnceTheDayHasElapsed() {
        when(invitations.findByEmailIgnoreCaseOrderByCreatedAtDesc("sam@example.com"))
                .thenReturn(List.of(invitation(1L, "sam@example.com", platformTeam,
                        Instant.now().minus(1, ChronoUnit.HOURS))));

        service.invite(new InviteRequest("sam@example.com", UserRole.MEMBER, 2L), TestUsers.alex(), BASE_URL);

        assertThat(issued().getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void anExpiredInvitationIsResentWithTheSameRoleAndGroup() {
        UserInvitation expired = invitation(1L, "sam@example.com", platformTeam,
                Instant.now().minus(1, ChronoUnit.HOURS));
        when(invitations.findById(1L)).thenReturn(Optional.of(expired));
        when(invitations.findByEmailIgnoreCaseOrderByCreatedAtDesc("sam@example.com")).thenReturn(List.of(expired));

        service.resend(1L, TestUsers.alex(), BASE_URL);

        UserInvitation replacement = issued();
        assertThat(replacement.getEmail()).isEqualTo("sam@example.com");
        assertThat(replacement.getGroup()).isEqualTo(platformTeam);
        assertThat(replacement.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void aStillValidInvitationIsNotResent() {
        UserInvitation live = invitation(1L, "sam@example.com", platformTeam,
                Instant.now().plus(6, ChronoUnit.HOURS));
        when(invitations.findById(1L)).thenReturn(Optional.of(live));

        assertThatThrownBy(() -> service.resend(1L, TestUsers.alex(), BASE_URL))
                .isInstanceOf(InvitationException.class)
                .hasMessageContaining("still valid");
        verify(invitations, never()).save(any());
    }

    @Test
    void aGroupAdminCannotTouchAnInvitationIntoAnotherGroup() {
        when(invitations.findById(1L)).thenReturn(Optional.of(
                invitation(1L, "sam@example.com", okafors, Instant.now().plus(6, ChronoUnit.HOURS))));

        assertThatThrownBy(() -> service.revoke(1L, TestUsers.alex()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void revokingStopsTheLinkWorking() {
        UserInvitation live = invitation(1L, "sam@example.com", platformTeam,
                Instant.now().plus(6, ChronoUnit.HOURS));
        when(invitations.findById(1L)).thenReturn(Optional.of(live));
        when(invitations.findByTokenHash(InvitationTokens.hash("a-token"))).thenReturn(Optional.of(live));

        service.revoke(1L, TestUsers.alex());

        assertThat(live.statusAt(Instant.now())).isEqualTo(InvitationStatus.REVOKED);
        assertThatThrownBy(() -> service.requirePending("a-token"))
                .isInstanceOf(InvalidInvitationException.class);
    }

    @Test
    void acceptingCreatesTheAccountTheInvitationDescribes() {
        UserInvitation live = invitation(1L, "sam@example.com", platformTeam,
                Instant.now().plus(6, ChronoUnit.HOURS));
        when(invitations.findByTokenHash(InvitationTokens.hash("a-token"))).thenReturn(Optional.of(live));
        when(userService.createInvited(any())).thenReturn(TestUsers.sam());

        service.accept("a-token", "Sam Diaz", "correct-horse-battery");

        ArgumentCaptor<NewAccountDetails> details = ArgumentCaptor.forClass(NewAccountDetails.class);
        verify(userService).createInvited(details.capture());
        assertThat(details.getValue().email()).isEqualTo("sam@example.com");
        assertThat(details.getValue().role()).isEqualTo(UserRole.MEMBER);
        assertThat(details.getValue().group()).isEqualTo(platformTeam);
        assertThat(live.isAccepted()).isTrue();
    }

    @Test
    void anInvitationCanOnlyBeAcceptedOnce() {
        UserInvitation used = invitation(1L, "sam@example.com", platformTeam,
                Instant.now().plus(6, ChronoUnit.HOURS));
        used.accept(Instant.now());
        when(invitations.findByTokenHash(InvitationTokens.hash("a-token"))).thenReturn(Optional.of(used));

        assertThatThrownBy(() -> service.accept("a-token", "Impostor", "correct-horse-battery"))
                .isInstanceOf(InvalidInvitationException.class);
        verify(userService, never()).createInvited(any());
    }

    @Test
    void anExpiredInvitationCannotBeAccepted() {
        when(invitations.findByTokenHash(InvitationTokens.hash("a-token"))).thenReturn(Optional.of(
                invitation(1L, "sam@example.com", platformTeam, Instant.now().minus(1, ChronoUnit.SECONDS))));

        assertThatThrownBy(() -> service.accept("a-token", "Sam Diaz", "correct-horse-battery"))
                .isInstanceOf(InvalidInvitationException.class);
        verify(userService, never()).createInvited(any());
    }

    @Test
    void anUnknownTokenGivesTheSameAnswerAsAnExpiredOne() {
        when(invitations.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requirePending("made-up"))
                .isInstanceOf(InvalidInvitationException.class)
                .hasMessageContaining("isn't valid");
    }

    @Test
    void aSuperAdminMayInviteIntoAnyGroupAndHandOutAnyRole() {
        UserAccount superAdmin = TestUsers.superAdmin();
        when(groups.findAllByOrderByNameAsc()).thenReturn(List.of(platformTeam, okafors));

        assertThat(service.invitableGroups(superAdmin)).containsExactly(platformTeam, okafors);
        assertThat(service.invitableRoles(superAdmin)).contains(UserRole.SUPER_ADMIN);
    }

    @Test
    void aGroupAdminOnlyEverSeesTheirOwnGroupAndCannotOfferSuperAdmin() {
        assertThat(service.invitableGroups(TestUsers.alex())).containsExactly(platformTeam);
        assertThat(service.invitableRoles(TestUsers.alex())).doesNotContain(UserRole.SUPER_ADMIN);
    }
}
