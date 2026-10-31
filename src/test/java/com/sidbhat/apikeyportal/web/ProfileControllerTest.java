package com.sidbhat.apikeyportal.web;

import static org.mockito.ArgumentMatchers.any;
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

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sidbhat.apikeyportal.security.PrincipalRefresher;
import com.sidbhat.apikeyportal.security.SecurityConfig;
import com.sidbhat.apikeyportal.user.EmailAlreadyUsedException;
import com.sidbhat.apikeyportal.user.IncorrectPasswordException;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.TestUsers;
import com.sidbhat.apikeyportal.user.UserAccountService;
import com.sidbhat.apikeyportal.user.WithPortalUser;

@WebMvcTest(controllers = ProfileController.class)
@Import(SecurityConfig.class)
class ProfileControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserAccountService userService;

    @MockitoBean
    private PrincipalRefresher principalRefresher;

    @BeforeEach
    void stubCurrentUser() {
        when(userService.getCurrent(any()))
                .thenAnswer(invocation -> TestUsers.accountFor((PortalUser) invocation.getArgument(0)));
    }

    @Test
    void redirectsAnonymousUsersToLogin() throws Exception {
        mvc.perform(get("/profile"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithPortalUser
    void showsYourOwnDetailsAndRoleButNeverAPassword() throws Exception {
        mvc.perform(get("/profile"))
                .andExpect(status().isOk())
                .andExpect(view().name("profile/view"))
                .andExpect(content().string(Matchers.containsString("alex@example.com")))
                .andExpect(content().string(Matchers.containsString("Platform team")))
                .andExpect(content().string(Matchers.containsString("Group admin")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("not-a-real-hash"))));
    }

    @Test
    @WithPortalUser
    void savesProfileChangesAndRefreshesTheSession() throws Exception {
        when(userService.updateProfile(eq(2L), any())).thenReturn(TestUsers.alex());

        mvc.perform(post("/profile").with(csrf())
                        .param("displayName", "Alex C.")
                        .param("email", "alex@example.com")
                        .param("jobTitle", "Backend engineer"))
                .andExpect(redirectedUrl("/profile"));

        verify(userService).updateProfile(eq(2L), any());
        verify(principalRefresher).refresh(any(), any(), any());
    }

    @Test
    @WithPortalUser
    void showsTheErrorOnTheEmailFieldWhenItIsTaken() throws Exception {
        when(userService.updateProfile(eq(2L), any()))
                .thenThrow(new EmailAlreadyUsedException("sam@example.com"));

        mvc.perform(post("/profile").with(csrf())
                        .param("displayName", "Alex Carter")
                        .param("email", "sam@example.com"))
                .andExpect(status().isOk())
                .andExpect(view().name("profile/view"))
                .andExpect(model().attributeHasFieldErrors("form", "email"));
    }

    @Test
    @WithPortalUser
    void changingThePasswordSignsYouOutAgain() throws Exception {
        mvc.perform(post("/profile/password").with(csrf())
                        .param("currentPassword", "old-password-1")
                        .param("newPassword", "new-password-99")
                        .param("confirmPassword", "new-password-99"))
                .andExpect(redirectedUrl("/login?passwordChanged"));

        verify(userService).changePassword(2L, "old-password-1", "new-password-99");
    }

    @Test
    @WithPortalUser
    void reportsAWrongCurrentPasswordOnTheField() throws Exception {
        when(userService.changePassword(eq(2L), eq("guessing"), any())).thenThrow(new IncorrectPasswordException());

        mvc.perform(post("/profile/password").with(csrf())
                        .param("currentPassword", "guessing")
                        .param("newPassword", "new-password-99")
                        .param("confirmPassword", "new-password-99"))
                .andExpect(status().isOk())
                .andExpect(view().name("profile/password"))
                .andExpect(model().attributeHasFieldErrors("form", "currentPassword"));
    }

    @Test
    @WithPortalUser
    void rejectsANewPasswordThatIsNotConfirmedOrIsUnchanged() throws Exception {
        mvc.perform(post("/profile/password").with(csrf())
                        .param("currentPassword", "old-password-1")
                        .param("newPassword", "old-password-1")
                        .param("confirmPassword", "mistyped-again"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "confirmPasswordMatching", "newPasswordDifferent"));

        verify(userService, never()).changePassword(any(), any(), any());
    }
}
