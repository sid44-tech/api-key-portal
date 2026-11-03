package io.github.apikeyportal;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The three probes as a monitor meets them: anonymous, status only - and as a
 * super admin meets them, with every component spelled out.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HealthEndpointIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void theHeartbeatAnswersAnonymously() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void readinessChecksTheDatabaseAndTheKeyButTellsAStrangerOnlyTheStatus() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(content().string(not(containsString("components"))));
    }

    @Test
    void theDeepProbeIsUpAndShowsNothingInternalToAStranger() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(content().string(not(containsString("sqlite"))))
                .andExpect(content().string(not(containsString("activeKey"))));
    }

    @Test
    @WithUserDetails("admin@example.com")
    void aSuperAdminSeesEveryComponent() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.diskSpace.status").value("UP"))
                .andExpect(jsonPath("$.components.encryption.status").value("UP"))
                .andExpect(jsonPath("$.components.encryption.details.configuredKeys").value(1))
                .andExpect(jsonPath("$.components.sso.status").value("UP"))
                .andExpect(jsonPath("$.components.sso.details.enabled").value(false));
    }

    @Test
    void nothingElseFromActuatorIsExposed() throws Exception {
        // env, beans, mappings and the rest would be a map of a portal full of secrets
        mvc.perform(get("/actuator/env")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/actuator/beans")).andExpect(status().is3xxRedirection());
    }
}
