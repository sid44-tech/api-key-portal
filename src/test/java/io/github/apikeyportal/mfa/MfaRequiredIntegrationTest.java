package io.github.apikeyportal.mfa;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import io.github.apikeyportal.user.PortalUser;
import io.github.apikeyportal.user.UserAccount;
import io.github.apikeyportal.user.UserAccountRepository;
import io.github.apikeyportal.user.UserGroup;
import io.github.apikeyportal.user.UserGroupRepository;
import io.github.apikeyportal.user.UserRole;

/** With two-factor required for everyone, an account without one is pinned to the enrolment page. */
@SpringBootTest(properties = "portal.security.mfa.required=true")
@AutoConfigureMockMvc
class MfaRequiredIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private UserGroupRepository groups;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UserGroup team;
    private UserAccount sam;
    private UserAccount ssoLinked;

    @BeforeEach
    void setUp() {
        team = groups.save(new UserGroup("Platform team"));
        sam = accounts.save(new UserAccount("sam@example.com", "Sam Diaz", passwordEncoder.encode("a-good-long-password"),
                UserRole.MEMBER, team));
        UserAccount linked = new UserAccount("jo@example.com", "Jo Okafor", passwordEncoder.encode("x"), UserRole.MEMBER, team);
        linked.setSsoSubject("subject-123");
        ssoLinked = accounts.save(linked);
    }

    @AfterEach
    void cleanUp() {
        accounts.delete(sam);
        accounts.delete(ssoLinked);
        groups.delete(team);
    }

    @Test
    void anUnenrolledPasswordAccountIsPinnedToTheEnrolmentPage() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(formLogin().user("sam@example.com").password("a-good-long-password"))
                .andExpect(redirectedUrl("/keys"))
                .andReturn().getRequest().getSession(false);

        mvc.perform(get("/keys").session(session)).andExpect(redirectedUrl("/mfa/setup"));
        mvc.perform(get("/profile").session(session)).andExpect(redirectedUrl("/mfa/setup"));
        mvc.perform(get("/mfa/setup").session(session)).andExpect(status().isOk());
        // the way out is still open
        mvc.perform(get("/actuator/health/liveness").session(session)).andExpect(status().isOk());
    }

    @Test
    void anAccountThatSignsInThroughTheProviderIsLeftAlone() throws Exception {
        mvc.perform(get("/keys").with(user(new PortalUser(ssoLinked)))).andExpect(status().isOk());
    }
}
