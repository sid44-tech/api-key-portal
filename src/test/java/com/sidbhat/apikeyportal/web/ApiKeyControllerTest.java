package com.sidbhat.apikeyportal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sidbhat.apikeyportal.activity.KeyOrigin;
import com.sidbhat.apikeyportal.integration.ServiceIntegrationService;
import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.key.ApiKeyDetails;
import com.sidbhat.apikeyportal.key.ApiKeyNotFoundException;
import com.sidbhat.apikeyportal.key.ApiKeyService;
import com.sidbhat.apikeyportal.key.ApiKeySort;
import com.sidbhat.apikeyportal.key.KeySummary;
import com.sidbhat.apikeyportal.security.SecurityConfig;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.TestUsers;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountService;
import com.sidbhat.apikeyportal.user.WithPortalUser;

@WebMvcTest(controllers = {ApiKeyController.class, ApiKeyValueController.class})
@Import(SecurityConfig.class)
class ApiKeyControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private ServiceIntegrationService integrationService;

    @MockitoBean
    private UserAccountService userService;

    private final UserAccount alex = TestUsers.alex();
    private final UserAccount sam = TestUsers.sam();

    @BeforeEach
    void stubCurrentUser() {
        when(userService.getCurrent(any()))
                .thenAnswer(invocation -> TestUsers.accountFor((PortalUser) invocation.getArgument(0)));
        when(userService.findVisibleTo(any())).thenReturn(List.of(alex, sam));
    }

    /** A key of Alex's, which Sam can see but not open. */
    private ApiKey alexKey() {
        ApiKey key = new ApiKey("CI", "GitHub", "fake-github-token-1234", LocalDate.now(), null);
        key.assignTo(alex, alex.getGroup());
        return key;
    }

    @Test
    void redirectsAnonymousUsersToLogin() throws Exception {
        mvc.perform(get("/keys"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithPortalUser
    void listsKeysWithoutRenderingValues() throws Exception {
        List<ApiKey> keys = List.of(alexKey());
        when(apiKeyService.search(any(), eq(null), eq(null), eq(null), eq(ApiKeySort.SERVICE), eq(0)))
                .thenReturn(new PageImpl<>(keys));
        when(apiKeyService.getSummary(any())).thenReturn(KeySummary.of(keys));

        mvc.perform(get("/keys"))
                .andExpect(status().isOk())
                .andExpect(view().name("keys/list"))
                .andExpect(content().string(Matchers.containsString("GitHub")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("fake-github-token-1234"))));
    }

    @Test
    @WithPortalUser("sam")
    void showsGroupMembersKeysButNotTheirValueControls() throws Exception {
        List<ApiKey> keys = List.of(alexKey());
        when(apiKeyService.search(any(), any(), any(), any(), any(), eq(0))).thenReturn(new PageImpl<>(keys));
        when(apiKeyService.getSummary(any())).thenReturn(KeySummary.of(keys));

        mvc.perform(get("/keys"))
                .andExpect(status().isOk())
                // Sam sees that the key exists and who holds it...
                .andExpect(content().string(Matchers.containsString("Alex Carter")))
                // ...but gets no reveal or copy button for it
                .andExpect(content().string(Matchers.not(Matchers.containsString("js-reveal"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("js-copy"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("fake-github-token-1234"))));
    }

    @Test
    @WithPortalUser
    void sendsContentSecurityPolicy() throws Exception {
        when(apiKeyService.search(any(), any(), any(), any(), any(), eq(0))).thenReturn(new PageImpl<>(List.of()));
        when(apiKeyService.getSummary(any())).thenReturn(KeySummary.of(List.of()));

        mvc.perform(get("/keys"))
                .andExpect(header().string("Content-Security-Policy",
                        Matchers.containsString("script-src 'self'")))
                .andExpect(header().string("Referrer-Policy", "same-origin"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    @WithPortalUser
    void createRedirectsToListOnSuccess() throws Exception {
        when(apiKeyService.create(any(), any(), any())).thenReturn(alexKey());

        mvc.perform(post("/keys").with(csrf())
                        .param("serviceName", "GitHub")
                        .param("name", "CI")
                        .param("keyValue", "ghp_abc")
                        .param("createdOn", "2021-03-01")
                        .param("expiresOn", "2021-09-01")
                        .param("notes", "repo scope"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/keys"))
                .andExpect(flash().attribute("success", "Added GitHub / CI."));

        ArgumentCaptor<ApiKeyDetails> details = ArgumentCaptor.forClass(ApiKeyDetails.class);
        verify(apiKeyService).create(details.capture(), eq(alex), eq(KeyOrigin.manual()));
        assertThat(details.getValue().getName()).isEqualTo("CI");
        assertThat(details.getValue().getServiceName()).isEqualTo("GitHub");
        assertThat(details.getValue().getKeyValue()).isEqualTo("ghp_abc");
        assertThat(details.getValue().getCreatedOn()).isEqualTo(LocalDate.of(2021, 3, 1));
        assertThat(details.getValue().getExpiresOn()).isEqualTo(LocalDate.of(2021, 9, 1));
        assertThat(details.getValue().getNotes()).isEqualTo("repo scope");
    }

    @Test
    @WithPortalUser
    void createRedisplaysFormWhenInvalid() throws Exception {
        mvc.perform(post("/keys").with(csrf())
                        .param("serviceName", "")
                        .param("name", "CI")
                        .param("keyValue", "ghp_abc")
                        .param("createdOn", "2021-03-01")
                        .param("expiresOn", "2021-02-01"))
                .andExpect(status().isOk())
                .andExpect(view().name("keys/form"))
                .andExpect(model().attributeHasFieldErrors("form", "serviceName", "expiresOnAfterCreatedOn"));

        verify(apiKeyService, never()).create(any(), any(), any());
    }

    @Test
    @WithPortalUser
    void theOwnerCanMuteExpiryReminders() throws Exception {
        when(apiKeyService.setExpiryNotificationsMuted(7L, true, alex)).thenReturn(alexKey());

        mvc.perform(post("/keys/7/notifications").with(csrf()).param("muted", "true"))
                .andExpect(redirectedUrl("/keys/7"))
                .andExpect(flash().attribute("success", Matchers.containsString("won't be emailed")));

        verify(apiKeyService).setExpiryNotificationsMuted(7L, true, alex);
    }

    @Test
    @WithPortalUser
    void unmutingIsTheSameSwitchTheOtherWay() throws Exception {
        when(apiKeyService.setExpiryNotificationsMuted(7L, false, alex)).thenReturn(alexKey());

        mvc.perform(post("/keys/7/notifications").with(csrf()).param("muted", "false"))
                .andExpect(redirectedUrl("/keys/7"))
                .andExpect(flash().attribute("success", Matchers.containsString("emailed again")));
    }

    @Test
    @WithPortalUser("sam")
    void somebodyElseCannotTouchTheOwnersReminders() throws Exception {
        when(apiKeyService.setExpiryNotificationsMuted(eq(7L), any(Boolean.class), any()))
                .thenThrow(new AccessDeniedException("Only the owner can change notifications for this key"));

        mvc.perform(post("/keys/7/notifications").with(csrf()).param("muted", "true"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithPortalUser
    void mutingRequiresACsrfToken() throws Exception {
        mvc.perform(post("/keys/7/notifications").param("muted", "true")).andExpect(status().isForbidden());

        verify(apiKeyService, never()).setExpiryNotificationsMuted(any(), any(Boolean.class), any());
    }

    @Test
    @WithPortalUser
    void deleteRequiresCsrfToken() throws Exception {
        mvc.perform(post("/keys/1/delete"))
                .andExpect(status().isForbidden());

        verify(apiKeyService, never()).delete(any(), any());
    }

    @Test
    @WithPortalUser
    void deleteRedirectsToList() throws Exception {
        when(apiKeyService.delete(eq(1L), any())).thenReturn(alexKey());

        mvc.perform(post("/keys/1/delete").with(csrf()))
                .andExpect(redirectedUrl("/keys"))
                .andExpect(flash().attribute("success", "Deleted GitHub / CI."));

        verify(apiKeyService).delete(1L, alex);
    }

    @Test
    @WithPortalUser
    void valueEndpointReturnsDecryptedValueAndIsNotCached() throws Exception {
        when(apiKeyService.revealValue(eq(1L), eq(alex))).thenReturn("ghp_abc");

        mvc.perform(get("/keys/1/value"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.value").value("ghp_abc"));
    }

    @Test
    @WithPortalUser("sam")
    void valueEndpointIsForbiddenForSomeoneElsesKey() throws Exception {
        when(apiKeyService.revealValue(eq(1L), any()))
                .thenThrow(new AccessDeniedException("Only the owner can reveal this key"));

        mvc.perform(get("/keys/1/value"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(Matchers.not(Matchers.containsString("ghp_abc"))));
    }

    @Test
    @WithPortalUser
    void editRedirectsToDetailForAKeyYouDoNotOwn() throws Exception {
        ApiKey samsKey = new ApiKey("Prod", "Stripe", "sk_1", LocalDate.now(), null);
        samsKey.assignTo(sam, sam.getGroup());
        when(apiKeyService.getById(eq(5L), any())).thenReturn(samsKey);

        mvc.perform(get("/keys/5/edit"))
                .andExpect(redirectedUrl("/keys/5"));
    }

    @Test
    @WithPortalUser
    void unknownKeyIs404() throws Exception {
        when(apiKeyService.getById(eq(99L), any())).thenThrow(new ApiKeyNotFoundException(99L));

        mvc.perform(get("/keys/99"))
                .andExpect(status().isNotFound());
    }
}
