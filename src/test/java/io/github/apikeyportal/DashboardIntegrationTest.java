package io.github.apikeyportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.github.apikeyportal.key.ApiKey;
import io.github.apikeyportal.key.ApiKeyRepository;
import io.github.apikeyportal.user.PortalUser;
import io.github.apikeyportal.user.UserAccount;
import io.github.apikeyportal.user.UserAccountRepository;
import io.github.apikeyportal.user.UserGroup;
import io.github.apikeyportal.user.UserGroupRepository;
import io.github.apikeyportal.user.UserRole;

/**
 * The dashboard against the real database, for two groups: what a member sees,
 * what they don't, and how a super admin moves between groups.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApiKeyRepository keys;

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private UserGroupRepository groups;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    private UserGroup platform;
    private UserGroup okafors;
    private UserAccount sam;
    private UserAccount jo;

    @BeforeEach
    void twoGroupsWithProblems() {
        platform = groups.save(new UserGroup("Platform team"));
        okafors = groups.save(new UserGroup("The Okafors"));
        sam = accounts.save(new UserAccount("sam@example.com", "Sam Diaz",
                passwordEncoder.encode("a-good-long-password"), UserRole.MEMBER, platform));
        jo = accounts.save(new UserAccount("jo@example.com", "Jo Okafor",
                passwordEncoder.encode("a-good-long-password"), UserRole.GROUP_ADMIN, okafors));

        // Platform: one expiring in ten days, one expired, one old and never revealed,
        // one old but revealed, one with a failed replacement
        save(sam, platform, "Deploy token", TODAY.plusDays(10));
        save(sam, platform, "Dead key", TODAY.minusDays(3));
        ApiKey unused = save(sam, platform, "Forgotten token", null);
        backdate(unused, 45);
        ApiKey used = save(sam, platform, "Busy token", null);
        backdate(used, 45);
        // markRevealed is the reveal service's, inside its transaction; the test sets the column itself
        jdbc.update("update api_key set last_revealed_at = ? where id = ?", Timestamp.from(Instant.now()), used.getId());
        ApiKey failing = save(sam, platform, "Reporting service", TODAY.plusDays(60));
        keys.markRotationFailed(failing.getId(), Instant.now(), "Azure AD rejected the credentials (HTTP 401)");

        // The Okafors: one expiring in five days
        save(jo, okafors, "Family photos API", TODAY.plusDays(5));
    }

    @AfterEach
    void cleanUp() {
        keys.deleteAll();
        accounts.delete(sam);
        accounts.delete(jo);
        groups.delete(platform);
        groups.delete(okafors);
    }

    private ApiKey save(UserAccount owner, UserGroup group, String name, LocalDate expiresOn) {
        ApiKey key = new ApiKey(name, "Stripe", "sk_" + name.hashCode(), TODAY.minusDays(100), expiresOn);
        key.assignTo(owner, group);
        return keys.save(key);
    }

    /** Auditing stamps added_at on save; the dashboard's "unused" needs older keys than that. */
    private void backdate(ApiKey key, int days) {
        jdbc.update("update api_key set added_at = ? where id = ?",
                Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)), key.getId());
    }

    private static RequestPostProcessor as(UserAccount account) {
        return user(new PortalUser(account));
    }

    private String dashboardAs(UserAccount account, String query) throws Exception {
        return mvc.perform(get("/dashboard" + query).with(as(account)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void aMemberSeesTheirGroupsProblemsAndNobodyElses() throws Exception {
        String page = dashboardAs(sam, "");

        assertThat(page).contains("Platform team")
                // expiring, with the day count; failed, with the provider's words; unused
                .contains("Deploy token").contains("in 10 days")
                .contains("Reporting service").contains("Azure AD rejected the credentials")
                .contains("Forgotten token")
                // revealed, and recently added, and expired are not "unused"
                .doesNotContain("Busy token")
                // the other group's key is invisible
                .doesNotContain("Family photos API");
        // the counts match: 5 keys, 1 expired
        assertThat(page).contains(">5</div>").contains(">1</div>");
        // never a value, on any list
        assertThat(page).doesNotContain("sk_");
    }

    @Test
    void anExpiringKeyWithAReplacementIsMarkedAsCovered() throws Exception {
        ApiKey expiring = keys.findAll().stream().filter(k -> k.getName().equals("Deploy token")).findFirst()
                .orElseThrow();
        ApiKey replacement = new ApiKey("Deploy token", "Stripe", "sk_new", TODAY, TODAY.plusDays(180));
        replacement.assignTo(sam, platform);
        replacement.recordProvenance(null, null, null, expiring.getId());
        keys.save(replacement);

        assertThat(dashboardAs(sam, "")).contains("Replacement ready");
    }

    @Test
    @WithUserDetails("admin@example.com")
    void theSuperAdminSeesEveryGroupAndCanNarrowToOne() throws Exception {
        String all = mvc.perform(get("/dashboard")).andReturn().getResponse().getContentAsString();
        assertThat(all).contains("across\n                    every group").contains("Deploy token")
                .contains("Family photos API")
                // the selector is theirs alone
                .contains("All groups").contains("The Okafors");

        String okaforsOnly = mvc.perform(get("/dashboard").param("group", okafors.getId().toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(okaforsOnly).contains("Family photos API").doesNotContain("Deploy token")
                .contains("<strong>The Okafors</strong>");
    }

    @Test
    void aMemberCannotPeekAtAnotherGroupThroughTheGroupParameter() throws Exception {
        String page = dashboardAs(sam, "?group=" + okafors.getId());

        assertThat(page).doesNotContain("Family photos API").contains("Deploy token")
                .doesNotContain("All groups");
    }

    @Test
    void theFailureShowsOnTheKeysOwnPageToo() throws Exception {
        ApiKey failing = keys.findAll().stream().filter(k -> k.getName().equals("Reporting service")).findFirst()
                .orElseThrow();

        String page = mvc.perform(get("/keys/{id}", failing.getId()).with(as(sam)))
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Automatic replacement failed").contains("Azure AD rejected the credentials");

        // and the next success clears it everywhere
        keys.clearRotationFailure(failing.getId());
        assertThat(dashboardAs(sam, "")).doesNotContain("Azure AD rejected the credentials");
    }

    @Test
    void anonymousVisitorsAreSentToSignIn() throws Exception {
        mvc.perform(get("/dashboard")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }
}
