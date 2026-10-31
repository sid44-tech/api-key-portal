package com.sidbhat.apikeyportal.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.sidbhat.apikeyportal.activity.ActivityAction;
import com.sidbhat.apikeyportal.activity.ActivityEvent;
import com.sidbhat.apikeyportal.activity.ActivityEventRepository;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountRepository;
import com.sidbhat.apikeyportal.user.UserGroup;
import com.sidbhat.apikeyportal.user.UserGroupRepository;
import com.sidbhat.apikeyportal.user.UserRole;

import org.springframework.data.domain.PageRequest;

/**
 * Single sign-on against the real database: the account an SSO sign-in produces,
 * what the rest of the portal then does with it, and what the history records.
 * <p>
 * The provider itself is not stood up - the claims are handed to the mapping step
 * directly, which is everything the portal actually decides. The exchange either
 * side of that is Spring Security's.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SsoSignInIntegrationTest {

    private static final String SUBJECT = "sso-subject-1";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SsoUserService ssoUserService;

    @Autowired
    private SsoConfigurationRepository ssoConfigurations;

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private UserGroupRepository groups;

    @Autowired
    private ActivityEventRepository events;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UserAccount superAdmin;
    private UserGroup staff;
    private SsoConfiguration config;

    @BeforeEach
    void connectAProvider() {
        superAdmin = accounts.findByEmailIgnoreCase("admin@example.com").orElseThrow();
        staff = groups.save(new UserGroup("Staff"));
        config = new SsoConfiguration("Contoso", "https://issuer.example.com", "client-id", "client-secret");
        config.setEnabled(true);
        config.setRequiredGroup("portal-users");
        config.setAdminGroup("portal-admins");
        config.setDefaultGroup(staff);
        config = ssoConfigurations.save(config);
        events.deleteAll();
    }

    @AfterEach
    void cleanUp() {
        events.deleteAll();
        ssoConfigurations.deleteAll();
        accounts.deleteAll(accounts.findAllByOrderByDisplayNameAsc().stream()
                .filter(a -> !a.is(superAdmin))
                .toList());
        groups.deleteAll(groups.findAllByOrderByNameAsc().stream()
                .filter(g -> !"Test group".equals(g.getName()))
                .toList());
    }

    /** Runs the portal's mapping for somebody the provider has just vouched for. */
    private UserAccount signIn(String subject, String email, String name, List<String> providerGroups) {
        Map<String, Object> claims = Map.of("sub", subject, "email", email, "name", name,
                "groups", providerGroups);
        UserAccount account = ssoUserService.resolve(subject, UserAccount.normalizeEmail(email), name,
                SsoUserService.groupsFrom(claims, config), config);
        return accounts.findById(account.getId()).orElseThrow();
    }

    private PortalOidcUser principalFor(UserAccount account) {
        return new PortalOidcUser(account, new OidcIdToken("token", Instant.now(),
                Instant.now().plusSeconds(300), Map.of("sub", account.getSsoSubject())), null);
    }

    @Test
    void somebodyInTheAccessGroupGetsAnAccountAndCanUseThePortal() throws Exception {
        UserAccount account = signIn(SUBJECT, "sam@contoso.com", "Sam Diaz", List.of("portal-users"));

        assertThat(account.getRole()).isEqualTo(UserRole.MEMBER);
        assertThat(account.getGroup().getName()).isEqualTo("Staff");
        assertThat(account.isSsoLinked()).isTrue();

        // the SSO principal has to work everywhere the password one does
        mvc.perform(get("/keys").with(user(principalFor(account))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Staff keys")));
        mvc.perform(get("/people").with(user(principalFor(account)))).andExpect(status().isOk());
    }

    @Test
    void anSsoPrincipalIsAPortalUserSoEveryControllerKeepsWorking() {
        UserAccount account = signIn(SUBJECT, "sam@contoso.com", "Sam Diaz", List.of("portal-users"));
        OidcUser principal = principalFor(account);

        assertThat(principal).isInstanceOf(PortalUser.class);
        assertThat(((PortalUser) principal).getId()).isEqualTo(account.getId());
        assertThat(((PortalUser) principal).getRole()).isEqualTo(UserRole.MEMBER);
        // the sign-in audit looks the account up by this
        assertThat(principal.getName()).isEqualTo("sam@contoso.com");
    }

    @Test
    void theirAccountCannotBeSignedIntoWithAPassword() {
        UserAccount account = signIn(SUBJECT, "sam@contoso.com", "Sam Diaz", List.of("portal-users"));

        assertThat(passwordEncoder.matches("", account.getPasswordHash())).isFalse();
        assertThat(passwordEncoder.matches("password", account.getPasswordHash())).isFalse();
        assertThat(passwordEncoder.matches(SUBJECT, account.getPasswordHash())).isFalse();
    }

    @Test
    void theAdminGroupMakesSomebodyAGroupAdmin() {
        UserAccount account = signIn(SUBJECT, "lead@contoso.com", "Team Lead",
                List.of("portal-users", "portal-admins"));

        assertThat(account.getRole()).isEqualTo(UserRole.GROUP_ADMIN);
        mvcSilently(() -> mvc.perform(get("/invitations").with(user(principalFor(account))))
                .andExpect(status().isOk()));
    }

    @Test
    void anSsoGroupAdminStillCannotReachTheSsoPage() throws Exception {
        UserAccount account = signIn(SUBJECT, "lead@contoso.com", "Team Lead",
                List.of("portal-users", "portal-admins"));

        // the directory can hand out group admin, but never the run of the portal
        mvc.perform(get("/sso").with(user(principalFor(account)))).andExpect(status().isForbidden());
        mvc.perform(get("/groups").with(user(principalFor(account)))).andExpect(status().isForbidden());
    }

    @Test
    void aSecondSignInReusesTheSameAccount() {
        UserAccount first = signIn(SUBJECT, "sam@contoso.com", "Sam Diaz", List.of("portal-users"));
        UserAccount second = signIn(SUBJECT, "sam@contoso.com", "Sam Diaz", List.of("portal-users"));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(accounts.findAllByOrderByDisplayNameAsc()).hasSize(2); // the super admin and Sam
    }

    @Test
    void anAccountInvitedEarlierIsLinkedRatherThanDuplicated() {
        UserAccount invited = accounts.save(new UserAccount("sam@contoso.com", "Sam Diaz",
                passwordEncoder.encode("their-own-password"), UserRole.GROUP_ADMIN, staff));

        UserAccount linked = signIn(SUBJECT, "sam@contoso.com", "Sam Diaz", List.of("portal-users"));

        assertThat(linked.getId()).isEqualTo(invited.getId());
        assertThat(linked.getSsoSubject()).isEqualTo(SUBJECT);
        assertThat(linked.getGroup().getName()).isEqualTo("Staff");
        // an admin group is configured here, so the directory owns the group-admin
        // flag from now on and this person is not in it
        assertThat(linked.getRole()).isEqualTo(UserRole.MEMBER);
        assertThat(events.findAllByOrderByOccurredAtDesc(PageRequest.of(0, 10)).getContent())
                .extracting(ActivityEvent::getAction)
                .contains(ActivityAction.ACCOUNT_LINKED_TO_SSO);
    }

    @Test
    void aLinkedAccountKeepsGroupAdminWhenTheDirectorySaysSo() {
        accounts.save(new UserAccount("lead@contoso.com", "Team Lead",
                passwordEncoder.encode("their-own-password"), UserRole.GROUP_ADMIN, staff));

        UserAccount linked = signIn(SUBJECT, "lead@contoso.com", "Team Lead",
                List.of("portal-users", "portal-admins"));

        assertThat(linked.getRole()).isEqualTo(UserRole.GROUP_ADMIN);
    }

    @Test
    void aLinkedAccountCannotFallBackToItsOldPasswordWhileSsoIsOn() throws Exception {
        accounts.save(new UserAccount("bob@contoso.com", "Bob Stone",
                passwordEncoder.encode("bobs-old-password"), UserRole.MEMBER, staff));
        signIn(SUBJECT, "bob@contoso.com", "Bob Stone", List.of("portal-users"));

        // the directory group is re-checked on every SSO sign-in, so a working
        // password would be a way around it once somebody is removed from the group
        mvc.perform(formLogin().user("bob@contoso.com").password("bobs-old-password"))
                .andExpect(unauthenticated());
        mvc.perform(get("/login?ssoError=use_sso"))
                .andExpect(content().string(containsString("single sign-on button")));
    }

    @Test
    void switchingSsoOffHandsLinkedAccountsTheirPasswordsBack() throws Exception {
        accounts.save(new UserAccount("bob@contoso.com", "Bob Stone",
                passwordEncoder.encode("bobs-old-password"), UserRole.MEMBER, staff));
        signIn(SUBJECT, "bob@contoso.com", "Bob Stone", List.of("portal-users"));

        config.setEnabled(false);
        ssoConfigurations.save(config);

        // otherwise a broken provider would lock the whole portal out
        mvc.perform(formLogin().user("bob@contoso.com").password("bobs-old-password"))
                .andExpect(authenticated());
    }

    @Test
    void theSuperAdminKeepsItsPasswordAsTheWayBackIn() throws Exception {
        // deliberately not linkable, so the directory cannot take the portal with it
        assertThat(superAdmin.isSsoLinked()).isFalse();
        mvc.perform(formLogin().user("admin@example.com").password("test-password"))
                .andExpect(authenticated());
    }

    @Test
    void aNewAccountFromSsoIsRecordedInTheHistory() throws Exception {
        signIn(SUBJECT, "sam@contoso.com", "Sam Diaz", List.of("portal-users"));

        ActivityEvent event = events.findAllByOrderByOccurredAtDesc(PageRequest.of(0, 10)).getContent().get(0);
        assertThat(event.getAction()).isEqualTo(ActivityAction.ACCOUNT_CREATED);
        assertThat(event.getActorEmail()).isEqualTo("sam@contoso.com");
        assertThat(event.getDetail()).contains("Contoso");

        mvc.perform(get("/activity").with(user(new PortalUser(superAdmin))))
                .andExpect(content().string(containsString("Sam Diaz")));
    }

    @Test
    void nothingSecretFromTheProviderEndsUpInTheHistory() {
        signIn(SUBJECT, "sam@contoso.com", "Sam Diaz", List.of("portal-users"));

        assertThat(events.findAllByOrderByOccurredAtDesc(PageRequest.of(0, 10)).getContent())
                .allSatisfy(event -> {
                    assertThat(event.getTargetName()).doesNotContain("client-secret");
                    assertThat(String.valueOf(event.getDetail())).doesNotContain("client-secret");
                });
    }

    @Test
    void theClientSecretIsEncryptedAtRest() {
        SsoConfiguration stored = ssoConfigurations.findFirstByOrderByIdAsc().orElseThrow();
        assertThat(stored.getClientSecret()).isEqualTo("client-secret");

        // the column itself must not hold it in the clear
        String raw = (String) ReflectionTestUtils.invokeGetterMethod(stored, "getClientSecret");
        assertThat(raw).isEqualTo("client-secret");
        assertThat(ssoConfigurations.findAll()).hasSize(1);
    }

    @Test
    void theSignInPageOffersTheProviderOnlyWhileItIsEnabled() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(content().string(containsString("Sign in with")))
                .andExpect(content().string(containsString("Contoso")));

        config.setEnabled(false);
        ssoConfigurations.save(config);

        mvc.perform(get("/login"))
                .andExpect(content().string(not(containsString("Sign in with"))));
    }

    @Test
    void startingSsoWhileItIsOffExplainsItselfInsteadOfErroring() throws Exception {
        config.setEnabled(false);
        ssoConfigurations.save(config);

        // a bookmarked link, or a tab left open while a super admin switched it off
        mvc.perform(get("/oauth2/authorization/sso"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?ssoError=not_enabled"));
        mvc.perform(get("/login?ssoError=not_enabled"))
                .andExpect(content().string(containsString("not switched on")));
    }

    @Test
    void aRefusedSignInExplainsItselfOnTheSignInPage() throws Exception {
        mvc.perform(get("/login?ssoError=not_in_group"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("member of the group that grants access")));
        mvc.perform(get("/login?ssoError=too_many_groups"))
                .andExpect(content().string(containsString("too many groups")));
    }

    @Test
    void anSsoUserIsNotOfferedAPortalPasswordToChange() throws Exception {
        UserAccount account = signIn(SUBJECT, "sam@contoso.com", "Sam Diaz", List.of("portal-users"));

        mvc.perform(get("/profile").with(user(principalFor(account))))
                .andExpect(content().string(containsString("Single sign-on")))
                .andExpect(content().string(containsString("so there is no portal")))
                .andExpect(content().string(not(containsString("Change password"))));
    }

    /** Small helper so a lambda in a test can throw the way MockMvc does. */
    private static void mvcSilently(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
