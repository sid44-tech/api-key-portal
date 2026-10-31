package com.sidbhat.apikeyportal.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Instant;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sidbhat.apikeyportal.activity.ActivityAction;
import com.sidbhat.apikeyportal.activity.ActivityEvent;
import com.sidbhat.apikeyportal.activity.ActivityService;
import com.sidbhat.apikeyportal.activity.KeySource;
import com.sidbhat.apikeyportal.security.SecurityConfig;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.TestUsers;
import com.sidbhat.apikeyportal.user.UserAccountService;
import com.sidbhat.apikeyportal.user.WithPortalUser;

@WebMvcTest(controllers = ActivityController.class)
@Import(SecurityConfig.class)
class ActivityControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ActivityService activityService;

    @MockitoBean
    private UserAccountService userService;

    @BeforeEach
    void stubCurrentUser() {
        when(userService.getCurrent(any()))
                .thenAnswer(invocation -> TestUsers.accountFor((PortalUser) invocation.getArgument(0)));
    }

    private void stubOneKeyEvent() {
        ActivityEvent event = new ActivityEvent(ActivityAction.KEY_CREATED, TestUsers.alex(), 42L,
                "Azure AD / Reporting service", KeySource.PROVISIONED, "Azure AD production", Instant.now());
        when(activityService.findVisibleTo(any(), any()))
                .thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 40), 1));
    }

    @Test
    void redirectsAnonymousUsersToLogin() throws Exception {
        mvc.perform(get("/activity"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithPortalUser("sam")
    void aPlainMemberCannotSeeTheHistory() throws Exception {
        mvc.perform(get("/activity")).andExpect(status().isForbidden());

        verify(activityService, never()).findVisibleTo(any(), any());
    }

    @Test
    @WithPortalUser("alex")
    void aGroupAdminSeesTheHistoryPage() throws Exception {
        stubOneKeyEvent();

        mvc.perform(get("/activity"))
                .andExpect(status().isOk())
                .andExpect(view().name("activity/list"))
                .andExpect(content().string(Matchers.containsString("Azure AD / Reporting service")))
                .andExpect(content().string(Matchers.containsString("Alex Carter")))
                // the point of the audit: what kind of key, and through what
                .andExpect(content().string(Matchers.containsString("Created via integration")))
                .andExpect(content().string(Matchers.containsString("Azure AD production")));
    }

    @Test
    @WithPortalUser("alex")
    void aGroupAdminIsNotOfferedTheGroupColumn() throws Exception {
        stubOneKeyEvent();

        mvc.perform(get("/activity"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("<th scope=\"col\">Group</th>"))));
    }

    @Test
    @WithPortalUser("superadmin")
    void aSuperAdminAlsoSeesWhichGroupEachEventBelongsTo() throws Exception {
        stubOneKeyEvent();

        mvc.perform(get("/activity"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Group")))
                .andExpect(content().string(Matchers.containsString("Platform team")));
    }

    @Test
    @WithPortalUser("alex")
    void showsAWelcomeWhenNothingHasHappenedYet() throws Exception {
        when(activityService.findVisibleTo(any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 40), 0));

        mvc.perform(get("/activity"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Nothing yet")));
    }

    @Test
    @WithPortalUser("alex")
    void aNegativePageNumberDoesNotReachTheRepository() throws Exception {
        stubOneKeyEvent();

        mvc.perform(get("/activity").param("page", "-5")).andExpect(status().isOk());

        verify(activityService).findVisibleTo(any(), org.mockito.ArgumentMatchers.argThat(
                pageable -> pageable.getPageNumber() == 0));
    }
}
