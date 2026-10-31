package com.sidbhat.apikeyportal.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sidbhat.apikeyportal.key.ApiKeyService;
import com.sidbhat.apikeyportal.security.SecurityConfig;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.TestUsers;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountService;
import com.sidbhat.apikeyportal.user.UserRole;
import com.sidbhat.apikeyportal.user.WithPortalUser;

@WebMvcTest(controllers = PeopleController.class)
@Import(SecurityConfig.class)
class PeopleControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserAccountService userService;

    @MockitoBean
    private ApiKeyService apiKeyService;

    private final UserAccount alex = TestUsers.alex();
    private final UserAccount sam = TestUsers.sam();

    @BeforeEach
    void stubCurrentUser() {
        when(userService.getCurrent(any()))
                .thenAnswer(invocation -> TestUsers.accountFor((PortalUser) invocation.getArgument(0)));
        when(apiKeyService.countByOwner(anyList())).thenReturn(Map.of(2L, 3L, 3L, 1L));
    }

    @Test
    void redirectsAnonymousUsersToLogin() throws Exception {
        mvc.perform(get("/people"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithPortalUser("sam")
    void aMemberSeesTheRestOfTheirGroup() throws Exception {
        when(userService.findVisibleTo(any())).thenReturn(List.of(alex, sam));

        mvc.perform(get("/people"))
                .andExpect(status().isOk())
                .andExpect(view().name("people/list"))
                .andExpect(content().string(Matchers.containsString("Alex Carter")))
                .andExpect(content().string(Matchers.containsString("Platform team")))
                // the group column is for super admins only
                .andExpect(content().string(Matchers.not(Matchers.containsString("The Okafors"))));
    }

    @Test
    @WithPortalUser("superadmin")
    void aSuperAdminSeesEveryoneWithTheirGroups() throws Exception {
        when(userService.findVisibleTo(any())).thenReturn(List.of(alex, sam, TestUsers.outsider()));

        mvc.perform(get("/people"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Everyone")))
                .andExpect(content().string(Matchers.containsString("Jo Okafor")))
                .andExpect(content().string(Matchers.containsString("The Okafors")));
    }

    @Test
    @WithPortalUser
    void personPageOffersAdministrationToTheirGroupAdmin() throws Exception {
        when(userService.getVisible(eq(3L), any())).thenReturn(sam);

        mvc.perform(get("/people/3"))
                .andExpect(status().isOk())
                .andExpect(view().name("people/detail"))
                .andExpect(content().string(Matchers.containsString("Sam Diaz")))
                .andExpect(content().string(Matchers.containsString("Administration")));
    }

    @Test
    @WithPortalUser("sam")
    void personPageOffersNoAdministrationToAPlainMember() throws Exception {
        when(userService.getVisible(eq(2L), any())).thenReturn(alex);

        mvc.perform(get("/people/2"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Alex Carter")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Administration"))));
    }

    @Test
    @WithPortalUser("outsider")
    void someoneFromAnotherGroupGetsA403() throws Exception {
        when(userService.getVisible(eq(2L), any())).thenThrow(new AccessDeniedException("Not in your group"));

        mvc.perform(get("/people/2"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithPortalUser
    void savesARoleChange() throws Exception {
        when(userService.getVisible(eq(3L), any())).thenReturn(sam);

        mvc.perform(post("/people/3").with(csrf())
                        .param("role", "GROUP_ADMIN")
                        .param("groupId", "2")
                        .param("enabled", "true"))
                .andExpect(redirectedUrl("/people/3"));

        verify(userService).administer(3L, alex, UserRole.GROUP_ADMIN, 2L, true);
    }

    @Test
    @WithPortalUser
    void showsARejectedChangeOnTheFormRatherThanA403Page() throws Exception {
        when(userService.getVisible(eq(3L), any())).thenReturn(sam);
        when(userService.administer(eq(3L), any(), any(), any(), eq(true)))
                .thenThrow(new AccessDeniedException("Only a super admin can grant super admin"));

        mvc.perform(post("/people/3").with(csrf())
                        .param("role", "SUPER_ADMIN")
                        .param("groupId", "2")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("people/detail"))
                .andExpect(content().string(Matchers.containsString("Only a super admin can grant super admin")));
    }

    @Test
    @WithPortalUser
    void administeringRequiresACsrfToken() throws Exception {
        mvc.perform(post("/people/3").param("role", "MEMBER"))
                .andExpect(status().isForbidden());

        verify(userService, never()).administer(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
