package com.sidbhat.apikeyportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.sidbhat.apikeyportal.key.ApiKeyRepository;

/**
 * End-to-end through the web layer against the real (in-memory) database,
 * encryption and security configuration.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiKeyLifecycleIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApiKeyRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    void logsInWithConfiguredCredentials() throws Exception {
        mvc.perform(formLogin().user("admin").password("dev-password")).andExpect(authenticated());
        mvc.perform(formLogin().user("admin").password("wrong")).andExpect(unauthenticated());
    }

    @Test
    @WithMockUser
    void createRevealEditAndDeleteKey() throws Exception {
        mvc.perform(post("/keys").with(csrf())
                        .param("serviceName", "Stripe")
                        .param("name", "Prod secret")
                        .param("keyValue", "sk_live_0123456789abcdef")
                        .param("createdOn", "2021-08-01"))
                .andExpect(status().is3xxRedirection());

        Long id = repository.findAll().get(0).getId();

        // stored encrypted
        String raw = jdbcTemplate.queryForObject("select key_value from api_key where id = ?", String.class, id);
        assertThat(raw).doesNotContain("sk_live");

        // listed, but value not rendered
        mvc.perform(get("/keys"))
                .andExpect(content().string(containsString("Prod secret")))
                .andExpect(content().string(not(containsString("sk_live_0123456789abcdef"))));

        // revealed on demand, and the reveal is recorded
        mvc.perform(get("/keys/{id}/value", id))
                .andExpect(jsonPath("$.value").value("sk_live_0123456789abcdef"));
        assertThat(repository.findById(id).orElseThrow().getLastRevealedAt()).isNotNull();

        // edit without touching the value
        mvc.perform(post("/keys/{id}", id).with(csrf())
                        .param("id", id.toString())
                        .param("serviceName", "Stripe")
                        .param("name", "Prod secret (rotated)")
                        .param("keyValue", "")
                        .param("createdOn", "2021-08-01")
                        .param("expiresOn", "2022-08-01"))
                .andExpect(status().is3xxRedirection());

        mvc.perform(get("/keys/{id}/value", id))
                .andExpect(jsonPath("$.value").value("sk_live_0123456789abcdef"));
        mvc.perform(get("/keys/{id}", id))
                .andExpect(content().string(containsString("Prod secret (rotated)")));

        mvc.perform(post("/keys/{id}/delete", id).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(repository.count()).isZero();
        mvc.perform(get("/keys/{id}", id)).andExpect(status().isNotFound());
    }
}
