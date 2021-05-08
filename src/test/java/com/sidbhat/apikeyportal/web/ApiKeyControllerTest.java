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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.key.ApiKeyNotFoundException;
import com.sidbhat.apikeyportal.key.ApiKeyService;
import com.sidbhat.apikeyportal.key.ApiKeySort;
import com.sidbhat.apikeyportal.key.KeySummary;

@WebMvcTest(controllers = {ApiKeyController.class, ApiKeyValueController.class})
class ApiKeyControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ApiKeyService apiKeyService;

    @Test
    void redirectsAnonymousUsersToLogin() throws Exception {
        mvc.perform(get("/keys"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser
    void listsKeysWithoutRenderingValues() throws Exception {
        List<ApiKey> keys = List.of(new ApiKey("CI", "GitHub", "ghp_supersecretvalue1234", LocalDate.now(), null));
        when(apiKeyService.search(null, null, ApiKeySort.SERVICE)).thenReturn(keys);
        when(apiKeyService.getSummary()).thenReturn(KeySummary.of(keys));

        mvc.perform(get("/keys"))
                .andExpect(status().isOk())
                .andExpect(view().name("keys/list"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("GitHub")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("ghp_supersecretvalue1234"))));
    }

    @Test
    @WithMockUser
    void createRedirectsToListOnSuccess() throws Exception {
        mvc.perform(post("/keys").with(csrf())
                        .param("serviceName", "GitHub")
                        .param("name", "CI")
                        .param("keyValue", "ghp_abc")
                        .param("createdOn", "2021-03-01")
                        .param("expiresOn", "2021-09-01"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/keys"));

        verify(apiKeyService).create("CI", "GitHub", "ghp_abc", LocalDate.of(2021, 3, 1),
                LocalDate.of(2021, 9, 1));
    }

    @Test
    @WithMockUser
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

        verify(apiKeyService, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    @WithMockUser
    void deleteRequiresCsrfToken() throws Exception {
        mvc.perform(post("/keys/1/delete"))
                .andExpect(status().isForbidden());

        verify(apiKeyService, never()).delete(any());
    }

    @Test
    @WithMockUser
    void deleteRedirectsToList() throws Exception {
        mvc.perform(post("/keys/1/delete").with(csrf()))
                .andExpect(redirectedUrl("/keys"));

        verify(apiKeyService).delete(1L);
    }

    @Test
    @WithMockUser
    void valueEndpointReturnsDecryptedValueAndIsNotCached() throws Exception {
        when(apiKeyService.getById(eq(1L))).thenReturn(
                new ApiKey("CI", "GitHub", "ghp_abc", LocalDate.now(), null));

        mvc.perform(get("/keys/1/value"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.value").value("ghp_abc"));
    }

    @Test
    @WithMockUser
    void unknownKeyIs404() throws Exception {
        when(apiKeyService.getById(99L)).thenThrow(new ApiKeyNotFoundException(99L));

        mvc.perform(get("/keys/99"))
                .andExpect(status().isNotFound());
    }
}
