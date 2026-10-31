package com.sidbhat.apikeyportal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.sidbhat.apikeyportal.invite.InvitationException;
import com.sidbhat.apikeyportal.invite.InvitationService;
import com.sidbhat.apikeyportal.invite.InviteRequest;
import com.sidbhat.apikeyportal.invite.IssuedInvitation;
import com.sidbhat.apikeyportal.invite.UserInvitation;
import com.sidbhat.apikeyportal.security.SecurityConfig;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.TestUsers;
import com.sidbhat.apikeyportal.user.UserAccountService;
import com.sidbhat.apikeyportal.user.UserGroup;
import com.sidbhat.apikeyportal.user.UserRole;
import com.sidbhat.apikeyportal.user.WithPortalUser;

@WebMvcTest(controllers = InvitationController.class)
@Import(SecurityConfig.class)
class InvitationControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private InvitationService invitationService;

    @MockitoBean
    private UserAccountService userService;

    private final UserGroup platformTeam = TestUsers.platformTeam();

    @BeforeEach
    void stubCurrentUser() {
        when(userService.getCurrent(any()))
                .thenAnswer(invocation -> TestUsers.accountFor((PortalUser) invocation.getArgument(0)));
        when(invitationService.invitableGroups(any())).thenReturn(List.of(platformTeam));
        when(invitationService.invitableRoles(any())).thenReturn(List.of(UserRole.GROUP_ADMIN, UserRole.MEMBER));
        when(invitationService.findVisibleTo(any())).thenReturn(List.of());
    }

    private UserInvitation pendingInvitation() {
        UserInvitation invitation = new UserInvitation("sam@example.com", "a-hash", UserRole.MEMBER, platformTeam,
                TestUsers.alex(), Instant.now().plus(1, ChronoUnit.DAYS));
        ReflectionTestUtils.setField(invitation, "id", 1L);
        return invitation;
    }

    @Test
    void redirectsAnonymousUsersToLogin() throws Exception {
        mvc.perform(get("/invitations"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithPortalUser("sam")
    void aPlainMemberCannotReachTheInvitePages() throws Exception {
        mvc.perform(get("/invitations")).andExpect(status().isForbidden());
        mvc.perform(get("/invitations/new")).andExpect(status().isForbidden());
        mvc.perform(post("/invitations").with(csrf())
                        .param("email", "new@example.com")
                        .param("role", "MEMBER")
                        .param("groupId", "2"))
                .andExpect(status().isForbidden());

        verify(invitationService, never()).invite(any(), any(), anyString());
    }

    @Test
    @WithPortalUser("alex")
    void aGroupAdminSeesTheInviteForm() throws Exception {
        mvc.perform(get("/invitations/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("invitations/form"))
                .andExpect(content().string(Matchers.containsString("Invite a user")));
    }

    @Test
    @WithPortalUser("alex")
    void sendsTheInvitationAndSaysSo() throws Exception {
        when(invitationService.invite(any(), any(), anyString()))
                .thenReturn(new IssuedInvitation(pendingInvitation(), "https://example.com/invite/tok", true));

        mvc.perform(post("/invitations").with(csrf())
                        .param("email", "sam@example.com")
                        .param("role", "MEMBER")
                        .param("groupId", "2"))
                .andExpect(redirectedUrl("/invitations"))
                .andExpect(flash().attribute("success", Matchers.containsString("emailed to sam@example.com")));

        ArgumentCaptor<InviteRequest> request = ArgumentCaptor.forClass(InviteRequest.class);
        verify(invitationService).invite(request.capture(), any(), anyString());
        assertThat(request.getValue().email()).isEqualTo("sam@example.com");
        assertThat(request.getValue().role()).isEqualTo(UserRole.MEMBER);
        assertThat(request.getValue().groupId()).isEqualTo(2L);
    }

    @Test
    @WithPortalUser("alex")
    void handsTheLinkOverWhenTheEmailCouldNotBeSent() throws Exception {
        when(invitationService.invite(any(), any(), anyString()))
                .thenReturn(new IssuedInvitation(pendingInvitation(), "https://example.com/invite/tok", false));

        mvc.perform(post("/invitations").with(csrf())
                        .param("email", "sam@example.com")
                        .param("role", "MEMBER")
                        .param("groupId", "2"))
                .andExpect(redirectedUrl("/invitations"))
                // the token is nowhere else: only its hash was stored
                .andExpect(flash().attribute("invitationLink", "https://example.com/invite/tok"))
                // a half-done job is a warning, not a success - the admin still has to deliver it
                .andExpect(flash().attribute("warning", Matchers.containsString("no email could be sent")))
                .andExpect(flash().attributeCount(2));
    }

    @Test
    @WithPortalUser("alex")
    void showsARejectedInvitationOnTheForm() throws Exception {
        when(invitationService.invite(any(), any(), anyString()))
                .thenThrow(new InvitationException("sam@example.com already has an account in the portal."));

        mvc.perform(post("/invitations").with(csrf())
                        .param("email", "sam@example.com")
                        .param("role", "MEMBER")
                        .param("groupId", "2"))
                .andExpect(status().isOk())
                .andExpect(view().name("invitations/form"))
                .andExpect(content().string(Matchers.containsString("already has an account")));
    }

    @Test
    @WithPortalUser("alex")
    void aDeniedInvitationIsAMessageRatherThanAnErrorPage() throws Exception {
        when(invitationService.invite(any(), any(), anyString()))
                .thenThrow(new AccessDeniedException("You can only invite people into your own group"));

        mvc.perform(post("/invitations").with(csrf())
                        .param("email", "sam@example.com")
                        .param("role", "MEMBER")
                        .param("groupId", "3"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("your own group")));
    }

    @Test
    @WithPortalUser("alex")
    void rejectsAnInvalidEmailAndAMissingGroup() throws Exception {
        mvc.perform(post("/invitations").with(csrf())
                        .param("email", "not-an-email")
                        .param("role", "MEMBER"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "email", "groupId"));

        verify(invitationService, never()).invite(any(), any(), anyString());
    }

    @Test
    @WithPortalUser("alex")
    void invitingRequiresACsrfToken() throws Exception {
        mvc.perform(post("/invitations")
                        .param("email", "sam@example.com")
                        .param("role", "MEMBER")
                        .param("groupId", "2"))
                .andExpect(status().isForbidden());

        verify(invitationService, never()).invite(any(), any(), anyString());
    }

    @Test
    @WithPortalUser("alex")
    void withdrawingAnInvitationReportsBack() throws Exception {
        when(invitationService.revoke(eq(1L), any())).thenReturn(pendingInvitation());

        mvc.perform(post("/invitations/1/revoke").with(csrf()))
                .andExpect(redirectedUrl("/invitations"))
                .andExpect(flash().attribute("success", Matchers.containsString("sam@example.com")));
    }

    @Test
    @WithPortalUser("alex")
    void aRefusedResendBecomesAnErrorMessage() throws Exception {
        when(invitationService.resend(eq(1L), any(), anyString()))
                .thenThrow(new InvitationException("That invitation is still valid until tomorrow"));

        mvc.perform(post("/invitations/1/resend").with(csrf()))
                .andExpect(redirectedUrl("/invitations"))
                .andExpect(flash().attribute("error", Matchers.containsString("still valid")));
    }
}
