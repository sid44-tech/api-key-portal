package com.sidbhat.apikeyportal.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.sidbhat.apikeyportal.security.LoginAttemptService;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountRepository;
import com.sidbhat.apikeyportal.user.UserGroup;
import com.sidbhat.apikeyportal.user.UserGroupRepository;
import com.sidbhat.apikeyportal.user.UserRole;
import com.sidbhat.apikeyportal.web.MfaController;

/**
 * Two-factor authentication as a person meets it, against the real database:
 * enrolling from the profile, the code page at sign-in, recovery codes,
 * switching off, and an administrator's reset.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MfaIntegrationTest {

    private static final String PASSWORD = "a-good-long-password";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private UserGroupRepository groups;

    @Autowired
    private MfaRecoveryCodeRepository recoveryCodes;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private LoginAttemptService attempts;

    @Autowired
    private JdbcTemplate jdbc;

    private UserGroup team;
    private UserAccount sam;
    private UserAccount alex;

    @BeforeEach
    void setUp() {
        attempts.forgetAll();
        team = groups.save(new UserGroup("Platform team"));
        sam = accounts.save(new UserAccount("sam@example.com", "Sam Diaz", passwordEncoder.encode(PASSWORD),
                UserRole.MEMBER, team));
        alex = accounts.save(new UserAccount("alex@example.com", "Alex Carter", passwordEncoder.encode(PASSWORD),
                UserRole.GROUP_ADMIN, team));
    }

    @AfterEach
    void cleanUp() {
        recoveryCodes.deleteAll();
        accounts.delete(sam);
        accounts.delete(alex);
        groups.delete(team);
        attempts.forgetAll();
        // the suite's admin is shared with every other test in this context and
        // with the re-encryption test, which rewrites whatever encrypted values
        // it finds; a second factor left on it by a failed test here would be
        // rotated onto a key the next context cannot open
        accounts.findByEmailIgnoreCase("admin@example.com").ifPresent(admin -> {
            if (admin.isMfaEnabled()) {
                admin.disableMfa();
                accounts.save(admin);
            }
        });
    }

    /** Enrols through the pages, as a person would, and hands back the recovery codes. */
    @SuppressWarnings("unchecked")
    private List<String> enrol(UserAccount account) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/mfa/setup").session(session).with(user(new PortalUser(account))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data:image/png;base64,")));
        String secret = (String) session.getAttribute(MfaController.PENDING_SECRET_ATTRIBUTE);
        assertThat(secret).isNotBlank();

        MvcResult confirmed = mvc.perform(post("/mfa/setup").session(session).with(user(new PortalUser(account))).with(csrf())
                        .param("code", Totp.codeFor(secret, Totp.currentStep())))
                .andExpect(redirectedUrl("/mfa/recovery-codes"))
                .andReturn();
        return (List<String>) confirmed.getFlashMap().get("recoveryCodes");
    }

    private UserAccount reload(UserAccount account) {
        return accounts.findById(account.getId()).orElseThrow();
    }

    /**
     * A code the account has not used yet. The enrolment code's step is burned
     * as "used" - correctly, it was - so a test signing in inside the same
     * thirty seconds has to move one step on, which the drift window allows.
     */
    private String currentCode(UserAccount account) {
        UserAccount fresh = reload(account);
        long step = Math.max(Totp.currentStep(), fresh.getMfaLastUsedStep() + 1);
        return Totp.codeFor(fresh.getMfaSecret(), step);
    }

    /** Signs in with the password and returns the half-authenticated session. */
    private MockHttpSession signInWithPassword(String email) throws Exception {
        MvcResult result = mvc.perform(formLogin().user(email).password(PASSWORD))
                .andExpect(redirectedUrl("/mfa/verify"))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void enrollingStoresTheSecretEncryptedAndTenHashedRecoveryCodesShownOnce() throws Exception {
        List<String> codes = enrol(sam);

        assertThat(codes).hasSize(10).allMatch(code -> code.matches("[a-z2-9]{5}-[a-z2-9]{5}"));
        UserAccount enrolled = reload(sam);
        assertThat(enrolled.isMfaEnabled()).isTrue();
        assertThat(enrolled.getMfaEnabledAt()).isNotNull();
        // encrypted at rest: the raw column is not the Base32 secret
        String raw = jdbc.queryForObject("select mfa_secret from user_account where id = ?", String.class, sam.getId());
        assertThat(raw).isNotEqualTo(enrolled.getMfaSecret()).doesNotContain(enrolled.getMfaSecret());
        // hashed, not stored: no code appears in the table
        List<String> hashes = jdbc.queryForList("select code_hash from mfa_recovery_code", String.class);
        assertThat(hashes).hasSize(10);
        for (String code : codes) {
            assertThat(hashes).doesNotContain(code).doesNotContain(code.replace("-", ""));
        }
        // and the page that showed them cannot be reloaded to show them again
        mvc.perform(get("/mfa/recovery-codes").with(user(new PortalUser(enrolled)))).andExpect(redirectedUrl("/mfa"));
    }

    @Test
    void aWrongCodeAtEnrolmentStoresNothing() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/mfa/setup").session(session).with(user(new PortalUser(sam))));

        mvc.perform(post("/mfa/setup").session(session).with(user(new PortalUser(sam))).with(csrf()).param("code", "000000"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("did not match")));

        assertThat(reload(sam).isMfaEnabled()).isFalse();
        assertThat(recoveryCodes.count()).isZero();
    }

    @Test
    void aCorrectPasswordLandsOnTheCodePageAndNothingElseIsServedUntilACodeIsAccepted() throws Exception {
        enrol(sam);
        MockHttpSession session = signInWithPassword("sam@example.com");

        // half in: every page, including the JSON one, bounces to the code page
        mvc.perform(get("/keys").session(session)).andExpect(redirectedUrl("/mfa/verify"));
        mvc.perform(get("/dashboard").session(session)).andExpect(redirectedUrl("/mfa/verify"));
        mvc.perform(post("/keys/1/value").session(session).with(csrf())).andExpect(redirectedUrl("/mfa/verify"));
        mvc.perform(get("/mfa/verify").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Enter your code")));

        String code = currentCode(sam);
        mvc.perform(post("/mfa/verify").session(session).with(csrf()).param("code", code))
                .andExpect(redirectedUrl("/keys"));

        // fully in now
        mvc.perform(get("/keys").session(session)).andExpect(status().isOk());
        assertThat(reload(sam).getLastLoginAt()).isNotNull();
    }

    @Test
    void anAcceptedCodeCannotBeReplayedInTheNextSignIn() throws Exception {
        enrol(sam);
        String code = currentCode(sam);
        MockHttpSession first = signInWithPassword("sam@example.com");
        mvc.perform(post("/mfa/verify").session(first).with(csrf()).param("code", code)).andExpect(redirectedUrl("/keys"));

        // the same code, still inside its thirty seconds, seen over a shoulder
        MockHttpSession second = signInWithPassword("sam@example.com");
        mvc.perform(post("/mfa/verify").session(second).with(csrf()).param("code", code))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("did not match")));
        mvc.perform(get("/keys").session(second)).andExpect(redirectedUrl("/mfa/verify"));
    }

    @Test
    void fiveWrongCodesEndTheSignInAndCountTowardsTheLockout() throws Exception {
        enrol(sam);
        MockHttpSession session = signInWithPassword("sam@example.com");

        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/mfa/verify").session(session).with(csrf()).param("code", "000000"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/mfa/verify").session(session).with(csrf()).param("code", "000000"))
                .andExpect(redirectedUrl("/login?mfaFailed"));

        // the half session is gone, and the guesses were counted like wrong passwords
        mvc.perform(get("/keys").session(session)).andExpect(redirectedUrl("/login"));
        assertThat(attempts.lockedUntil("sam@example.com", "127.0.0.1")).isNull(); // the suite's limit is very high
    }

    @Test
    void aRecoveryCodeSignsInOnceAndIsThenSpent() throws Exception {
        List<String> codes = enrol(sam);
        String recovery = codes.get(0);

        MockHttpSession session = signInWithPassword("sam@example.com");
        mvc.perform(post("/mfa/verify").session(session).with(csrf()).param("recoveryCode", recovery.toUpperCase()))
                .andExpect(redirectedUrl("/keys"));
        assertThat(recoveryCodes.countByUserId(sam.getId())).isEqualTo(9);

        MockHttpSession again = signInWithPassword("sam@example.com");
        mvc.perform(post("/mfa/verify").session(again).with(csrf()).param("recoveryCode", recovery))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("has been used")));
    }

    @Test
    void switchingOffNeedsThePasswordAndACodeBoth() throws Exception {
        enrol(sam);
        UserAccount enrolled = reload(sam);

        // right code, wrong password: refused
        mvc.perform(post("/mfa/disable").with(user(new PortalUser(enrolled))).with(csrf())
                        .param("currentPassword", "not-it").param("code", currentCode(sam)))
                .andExpect(redirectedUrl("/mfa"))
                .andExpect(flash().attribute("error", "The current password is not right."));
        assertThat(reload(sam).isMfaEnabled()).isTrue();

        // right password, wrong code: refused
        mvc.perform(post("/mfa/disable").with(user(new PortalUser(enrolled))).with(csrf())
                        .param("currentPassword", PASSWORD).param("code", "000000"))
                .andExpect(flash().attribute("error", "That code did not match."));
        assertThat(reload(sam).isMfaEnabled()).isTrue();

        // both: off, and the recovery codes go with it
        mvc.perform(post("/mfa/disable").with(user(new PortalUser(enrolled))).with(csrf())
                        .param("currentPassword", PASSWORD).param("code", currentCode(sam)))
                .andExpect(redirectedUrl("/profile"));
        assertThat(reload(sam).isMfaEnabled()).isFalse();
        assertThat(recoveryCodes.countByUserId(sam.getId())).isZero();

        // and a password alone signs in again
        mvc.perform(formLogin().user("sam@example.com").password(PASSWORD)).andExpect(redirectedUrl("/keys"));
    }

    @Test
    void aSuperAdminCanResetSomeoneElsesSecondFactorAndAGroupAdminCannot() throws Exception {
        enrol(sam);
        UserAccount admin = accounts.findByEmailIgnoreCase("admin@example.com").orElseThrow();

        mvc.perform(post("/people/{id}/mfa/reset", sam.getId()).with(user(new PortalUser(alex))).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(reload(sam).isMfaEnabled()).isTrue();

        mvc.perform(post("/people/{id}/mfa/reset", sam.getId()).with(user(new PortalUser(admin))).with(csrf()))
                .andExpect(redirectedUrl("/people/" + sam.getId()));
        assertThat(reload(sam).isMfaEnabled()).isFalse();
        assertThat(recoveryCodes.countByUserId(sam.getId())).isZero();

        // never their own: the break-glass account's second factor is not one click to remove
        enrol(admin);
        mvc.perform(post("/people/{id}/mfa/reset", admin.getId()).with(user(new PortalUser(reload(admin)))).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(reload(admin).isMfaEnabled()).isTrue();
        // leave the suite's admin as it was found
        mvc.perform(post("/mfa/disable").with(user(new PortalUser(reload(admin)))).with(csrf())
                .param("currentPassword", "test-password").param("code", currentCode(admin)));
        assertThat(reload(admin).isMfaEnabled()).isFalse();
    }

    @Test
    void theProfileOffersSetUpAndThenManage() throws Exception {
        assertThat(mvc.perform(get("/profile").with(user(new PortalUser(sam)))).andReturn().getResponse().getContentAsString())
                .contains("Two-factor authentication").contains("Set up");
        enrol(sam);
        assertThat(mvc.perform(get("/profile").with(user(new PortalUser(reload(sam))))).andReturn().getResponse().getContentAsString())
                .contains("Manage");
        assertThat(mvc.perform(get("/mfa").with(user(new PortalUser(reload(sam))))).andReturn().getResponse().getContentAsString())
                .contains("10</span> of 10 recovery codes unused");
    }
}
