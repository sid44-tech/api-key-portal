package com.sidbhat.apikeyportal.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sidbhat.apikeyportal.invite.InvalidInvitationException;
import com.sidbhat.apikeyportal.invite.InvitationService;
import com.sidbhat.apikeyportal.invite.UserInvitation;
import com.sidbhat.apikeyportal.security.SecurityConfig;
import com.sidbhat.apikeyportal.user.TestUsers;
import com.sidbhat.apikeyportal.user.UserRole;

@WebMvcTest(controllers = AcceptInviteController.class)
@Import(SecurityConfig.class)
class AcceptInviteControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private InvitationService invitationService;

    private UserInvitation pending() {
        return new UserInvitation("sam@example.com", "a-hash", UserRole.MEMBER, TestUsers.platformTeam(),
                TestUsers.alex(), Instant.now().plus(1, ChronoUnit.DAYS));
    }

    @Test
    void theAcceptPageIsReachableWithoutSigningIn() throws Exception {
        when(invitationService.requirePending("a-token")).thenReturn(pending());

        mvc.perform(get("/invite/a-token"))
                .andExpect(status().isOk())
                .andExpect(view().name("invite/accept"))
                .andExpect(content().string(Matchers.containsString("Set up your account")))
                // the invitation decides these, so they are shown rather than asked for
                .andExpect(content().string(Matchers.containsString("sam@example.com")))
                .andExpect(content().string(Matchers.containsString("Platform team")));
    }

    @Test
    void anExpiredLinkExplainsItselfInsteadOfErroring() throws Exception {
        when(invitationService.requirePending("a-token"))
                .thenThrow(new InvalidInvitationException("This invitation link isn't valid."));

        mvc.perform(get("/invite/a-token"))
                .andExpect(status().isOk())
                .andExpect(view().name("invite/invalid"))
                .andExpect(content().string(Matchers.containsString("can't be used")));
    }

    @Test
    void createsTheAccountAndSendsThemToSignIn() throws Exception {
        when(invitationService.requirePending("a-token")).thenReturn(pending());
        when(invitationService.accept(eq("a-token"), anyString(), anyString())).thenReturn(TestUsers.sam());

        mvc.perform(post("/invite/a-token").with(csrf())
                        .param("displayName", "Sam Diaz")
                        .param("password", "correct-horse-battery")
                        .param("confirmPassword", "correct-horse-battery"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?registered"));

        verify(invitationService).accept("a-token", "Sam Diaz", "correct-horse-battery");
    }

    @Test
    void theTokenIsCheckedAgainOnSubmitNotJustOnTheWayIn() throws Exception {
        // the page could have been sitting open past the invitation's day
        when(invitationService.requirePending("a-token"))
                .thenThrow(new InvalidInvitationException("This invitation link isn't valid."));

        mvc.perform(post("/invite/a-token").with(csrf())
                        .param("displayName", "Sam Diaz")
                        .param("password", "correct-horse-battery")
                        .param("confirmPassword", "correct-horse-battery"))
                .andExpect(view().name("invite/invalid"));

        verify(invitationService, never()).accept(anyString(), anyString(), anyString());
    }

    @Test
    void rejectsMismatchedPasswordConfirmation() throws Exception {
        when(invitationService.requirePending("a-token")).thenReturn(pending());

        mvc.perform(post("/invite/a-token").with(csrf())
                        .param("displayName", "Sam Diaz")
                        .param("password", "correct-horse-battery")
                        .param("confirmPassword", "something-else"))
                .andExpect(status().isOk())
                .andExpect(view().name("invite/accept"))
                .andExpect(model().attributeHasFieldErrors("form", "confirmPasswordMatching"));

        verify(invitationService, never()).accept(anyString(), anyString(), anyString());
    }

    @Test
    void rejectsAShortPassword() throws Exception {
        when(invitationService.requirePending("a-token")).thenReturn(pending());

        mvc.perform(post("/invite/a-token").with(csrf())
                        .param("displayName", "Sam Diaz")
                        .param("password", "short")
                        .param("confirmPassword", "short"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "password"));

        verify(invitationService, never()).accept(anyString(), anyString(), anyString());
    }

    @Test
    void acceptingRequiresACsrfToken() throws Exception {
        mvc.perform(post("/invite/a-token")
                        .param("displayName", "Sam Diaz")
                        .param("password", "correct-horse-battery")
                        .param("confirmPassword", "correct-horse-battery"))
                .andExpect(status().isForbidden());

        verify(invitationService, never()).accept(anyString(), anyString(), anyString());
    }

    @Test
    void theRoleAndGroupCannotBeTalkedUpFromTheForm() throws Exception {
        when(invitationService.requirePending("a-token")).thenReturn(pending());
        when(invitationService.accept(eq("a-token"), anyString(), anyString())).thenReturn(TestUsers.sam());

        mvc.perform(post("/invite/a-token").with(csrf())
                        .param("displayName", "Sam Diaz")
                        .param("password", "correct-horse-battery")
                        .param("confirmPassword", "correct-horse-battery")
                        .param("role", "SUPER_ADMIN")
                        .param("groupId", "99")
                        .param("email", "someone.else@example.com"))
                .andExpect(redirectedUrl("/login?registered"));

        // nothing but the name and password is carried across; the rest is the invitation's
        verify(invitationService).accept("a-token", "Sam Diaz", "correct-horse-battery");
    }
}
