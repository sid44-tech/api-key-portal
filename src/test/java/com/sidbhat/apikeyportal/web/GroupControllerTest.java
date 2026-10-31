package com.sidbhat.apikeyportal.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
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

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sidbhat.apikeyportal.group.GroupNotEmptyException;
import com.sidbhat.apikeyportal.group.GroupService;
import com.sidbhat.apikeyportal.group.GroupUsage;
import com.sidbhat.apikeyportal.security.SecurityConfig;
import com.sidbhat.apikeyportal.user.GroupNameAlreadyUsedException;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccountService;
import com.sidbhat.apikeyportal.user.TestUsers;
import com.sidbhat.apikeyportal.user.WithPortalUser;

@WebMvcTest(controllers = GroupController.class)
@Import(SecurityConfig.class)
class GroupControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private GroupService groupService;

    @MockitoBean
    private UserAccountService userService;

    @BeforeEach
    void stubGroups() {
        when(userService.getCurrent(any()))
                .thenAnswer(invocation -> TestUsers.accountFor((PortalUser) invocation.getArgument(0)));
        when(groupService.findAllWithUsage()).thenReturn(List.of(
                new GroupUsage(TestUsers.platformTeam(), 2, 5, 1, 0),
                new GroupUsage(TestUsers.group(9L, "Nobody here"), 0, 0, 0, 0)));
    }

    @Test
    void redirectsAnonymousUsersToLogin() throws Exception {
        mvc.perform(get("/groups"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithPortalUser("alex")
    void aGroupAdminCannotManageGroups() throws Exception {
        mvc.perform(get("/groups")).andExpect(status().isForbidden());
        mvc.perform(post("/groups").with(csrf()).param("name", "Mine")).andExpect(status().isForbidden());
        mvc.perform(post("/groups/2/delete").with(csrf())).andExpect(status().isForbidden());

        verify(groupService, never()).create(anyString(), any());
        verify(groupService, never()).delete(any());
    }

    @Test
    @WithPortalUser("superadmin")
    void listsEveryGroupWithWhatItHolds() throws Exception {
        mvc.perform(get("/groups"))
                .andExpect(status().isOk())
                .andExpect(view().name("groups/list"))
                .andExpect(content().string(Matchers.containsString("Platform team")))
                .andExpect(content().string(Matchers.containsString("Nobody here")));
    }

    @Test
    @WithPortalUser("superadmin")
    void addsAGroup() throws Exception {
        when(groupService.create(eq("Platform team"), any())).thenReturn(TestUsers.platformTeam());

        mvc.perform(post("/groups").with(csrf()).param("name", "Platform team"))
                .andExpect(redirectedUrl("/groups"))
                .andExpect(flash().attribute("success", Matchers.containsString("Platform team")));
    }

    @Test
    @WithPortalUser("superadmin")
    void showsTheErrorWhenTheNameIsTaken() throws Exception {
        when(groupService.create(anyString(), any())).thenThrow(new GroupNameAlreadyUsedException("Platform team"));

        mvc.perform(post("/groups").with(csrf()).param("name", "Platform team"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "name"));
    }

    @Test
    @WithPortalUser("superadmin")
    void rejectsABlankName() throws Exception {
        mvc.perform(post("/groups").with(csrf()).param("name", " "))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "name"));

        verify(groupService, never()).create(anyString(), any());
    }

    @Test
    @WithPortalUser("superadmin")
    void deletesAnEmptyGroup() throws Exception {
        mvc.perform(post("/groups/9/delete").with(csrf()))
                .andExpect(redirectedUrl("/groups"))
                .andExpect(flash().attributeExists("success"));

        verify(groupService).delete(9L);
    }

    @Test
    @WithPortalUser("superadmin")
    void saysWhatIsInTheWayWhenTheGroupIsNotEmpty() throws Exception {
        doThrow(new GroupNotEmptyException(new GroupUsage(TestUsers.platformTeam(), 2, 5, 0, 0)))
                .when(groupService).delete(2L);

        mvc.perform(post("/groups/2/delete").with(csrf()))
                .andExpect(redirectedUrl("/groups"))
                .andExpect(flash().attribute("error", Matchers.allOf(
                        Matchers.containsString("2 members"), Matchers.containsString("5 keys"))));
    }

    @Test
    @WithPortalUser("superadmin")
    void changingGroupsRequiresACsrfToken() throws Exception {
        mvc.perform(post("/groups").param("name", "Platform team")).andExpect(status().isForbidden());
        mvc.perform(post("/groups/9/delete")).andExpect(status().isForbidden());

        verify(groupService, never()).create(anyString(), any());
        verify(groupService, never()).delete(any());
    }
}
