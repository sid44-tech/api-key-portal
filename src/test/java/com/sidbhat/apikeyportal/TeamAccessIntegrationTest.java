package com.sidbhat.apikeyportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.sidbhat.apikeyportal.invite.UserInvitationRepository;
import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.key.ApiKeyRepository;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountRepository;
import com.sidbhat.apikeyportal.user.UserGroup;
import com.sidbhat.apikeyportal.user.UserGroupRepository;
import com.sidbhat.apikeyportal.user.UserRole;

/**
 * The whole point of groups, end to end: Alex signs up and adds a key, Sam (same
 * group) can see that it exists but not what it is, and Jo (another group) can't
 * see it at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TeamAccessIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private UserGroupRepository groups;

    @Autowired
    private ApiKeyRepository keys;

    @Autowired
    private UserInvitationRepository invitations;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UserAccount alex;
    private UserAccount sam;
    private UserAccount jo;

    @BeforeEach
    void setUpTwoGroups() {
        UserGroup platformTeam = groups.save(new UserGroup("Platform team"));
        alex = addAccount("alex@example.com", "Alex Carter", UserRole.GROUP_ADMIN, platformTeam);
        sam = addAccount("sam@example.com", "Sam Diaz", UserRole.MEMBER, platformTeam);
        jo = addAccount("jo@example.com", "Jo Okafor", UserRole.GROUP_ADMIN,
                groups.save(new UserGroup("The Okafors")));
    }

    @AfterEach
    void cleanUp() {
        keys.deleteAll();
        invitations.deleteAll();
        accounts.deleteAll(accounts.findAllByOrderByDisplayNameAsc().stream()
                .filter(a -> !a.isSuperAdmin())
                .toList());
        // One test moves the super admin into Platform team, which is about to be
        // deleted. Put them back first: SQLite doesn't enforce the foreign key, so
        // otherwise they are left pointing at a group that no longer exists and the
        // next test in this context inherits a broken account.
        groups.findByNameIgnoreCase("Test group").ifPresent(testGroup ->
                accounts.findAllByOrderByDisplayNameAsc().stream()
                        .filter(UserAccount::isSuperAdmin)
                        .forEach(admin -> {
                            admin.setGroup(testGroup);
                            accounts.save(admin);
                        }));
        groups.deleteAll(groups.findAllByOrderByNameAsc().stream()
                .filter(g -> !"Test group".equals(g.getName()))
                .toList());
    }

    private UserAccount addAccount(String email, String name, UserRole role, UserGroup group) {
        return accounts.save(new UserAccount(email, name, passwordEncoder.encode("correct-horse-battery"),
                role, group));
    }

    private RequestPostProcessor as(UserAccount account) {
        return user(new PortalUser(accounts.findById(account.getId()).orElseThrow()));
    }

    private Long addStripeKeyAsAlex() throws Exception {
        mvc.perform(post("/keys").with(as(alex)).with(csrf())
                        .param("serviceName", "Stripe")
                        .param("name", "Prod secret")
                        .param("keyValue", "fake-stripe-key-0123456789")
                        .param("createdOn", "2021-08-01"))
                .andExpect(status().is3xxRedirection());
        return keys.findAll().get(0).getId();
    }

    @Test
    void anAccountSignsInWithItsOwnPassword() throws Exception {
        mvc.perform(formLogin().user("alex@example.com").password("correct-horse-battery"))
                .andExpect(authenticated());
        mvc.perform(formLogin().user("alex@example.com").password("wrong")).andExpect(unauthenticated());

        assertThat(alex.getRole()).isEqualTo(UserRole.GROUP_ADMIN);
        assertThat(alex.getGroup().getName()).isEqualTo("Platform team");
        assertThat(alex.getPasswordHash()).startsWith("$2").doesNotContain("correct-horse-battery");
    }

    @Test
    void thereIsNoWayToCreateAnAccountWithoutAnInvitation() throws Exception {
        // the sign-up page is gone, and what's left of the portal needs a session
        mvc.perform(get("/signup")).andExpect(redirectedUrl("/login"));
        mvc.perform(post("/signup").with(csrf())
                        .param("displayName", "Interloper")
                        .param("email", "interloper@example.com")
                        .param("groupName", "Mine now")
                        .param("password", "correct-horse-battery")
                        .param("confirmPassword", "correct-horse-battery"))
                .andExpect(status().is3xxRedirection());

        assertThat(accounts.findByEmailIgnoreCase("interloper@example.com")).isEmpty();
    }

    @Test
    void newKeysBelongToTheirCreatorAndTheirGroup() throws Exception {
        addStripeKeyAsAlex();

        ApiKey key = keys.findAll().get(0);
        assertThat(key.getOwnerId()).isEqualTo(alex.getId());
        assertThat(key.getGroup().getName()).isEqualTo("Platform team");
    }

    @Test
    void aGroupMemberSeesTheKeyAndItsOwnerButNotItsValue() throws Exception {
        Long id = addStripeKeyAsAlex();

        mvc.perform(get("/keys").with(as(sam)))
                .andExpect(content().string(containsString("Prod secret")))
                .andExpect(content().string(containsString("Alex Carter")))
                .andExpect(content().string(not(containsString("fake-stripe-key-0123456789"))));

        mvc.perform(get("/keys/{id}", id).with(as(sam)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Prod secret")))
                .andExpect(content().string(containsString("Only")));

        // the value endpoint is the only way to a key value, and it is owner-only
        mvc.perform(get("/keys/{id}/value", id).with(as(sam)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("fake-stripe-key-0123456789"))));
    }

    @Test
    void aGroupMemberCannotEditOrDeleteSomeoneElsesKey() throws Exception {
        Long id = addStripeKeyAsAlex();

        mvc.perform(post("/keys/{id}", id).with(as(sam)).with(csrf())
                        .param("id", id.toString())
                        .param("serviceName", "Stripe")
                        .param("name", "Hijacked")
                        .param("keyValue", "")
                        .param("createdOn", "2021-08-01"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/keys/{id}/delete", id).with(as(sam)).with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(keys.findById(id)).hasValueSatisfying(k -> assertThat(k.getName()).isEqualTo("Prod secret"));
    }

    @Test
    void theOwnerCanStillRevealTheirOwnKey() throws Exception {
        Long id = addStripeKeyAsAlex();

        mvc.perform(get("/keys/{id}/value", id).with(as(alex)))
                .andExpect(jsonPath("$.value").value("fake-stripe-key-0123456789"));
    }

    @Test
    void someoneInAnotherGroupSeesNothingAtAll() throws Exception {
        Long id = addStripeKeyAsAlex();

        mvc.perform(get("/keys").with(as(jo)))
                .andExpect(content().string(not(containsString("Prod secret"))));
        mvc.perform(get("/keys/{id}", id).with(as(jo)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/people/{id}", alex.getId()).with(as(jo)))
                .andExpect(status().isForbidden());
    }

    @Test
    void thePeoplePageShowsTheGroupToItsMembersAndEveryoneToASuperAdmin() throws Exception {
        UserAccount superAdmin = accounts.findByEmailIgnoreCase("admin@example.com").orElseThrow();

        mvc.perform(get("/people").with(as(sam)))
                .andExpect(content().string(containsString("Alex Carter")))
                .andExpect(content().string(not(containsString("Jo Okafor"))));

        mvc.perform(get("/people").with(as(superAdmin)))
                .andExpect(content().string(containsString("Alex Carter")))
                .andExpect(content().string(containsString("Jo Okafor")));
    }

    @Test
    void changingYourPasswordTakesEffectOnTheNextSignIn() throws Exception {
        mvc.perform(post("/profile/password").with(as(alex)).with(csrf())
                        .param("currentPassword", "correct-horse-battery")
                        .param("newPassword", "a-brand-new-passphrase")
                        .param("confirmPassword", "a-brand-new-passphrase"))
                .andExpect(redirectedUrl("/login?passwordChanged"));

        mvc.perform(formLogin().user("alex@example.com").password("correct-horse-battery"))
                .andExpect(unauthenticated());
        mvc.perform(formLogin().user("alex@example.com").password("a-brand-new-passphrase"))
                .andExpect(authenticated());
    }

    @Test
    void updatingYourProfileChangesTheEmailYouSignInWith() throws Exception {
        mvc.perform(post("/profile").with(as(alex)).with(csrf())
                        .param("displayName", "Alex Carter")
                        .param("email", "alex.carter@example.com")
                        .param("jobTitle", "Backend engineer"))
                .andExpect(redirectedUrl("/profile"));

        mvc.perform(formLogin().user("alex.carter@example.com").password("correct-horse-battery"))
                .andExpect(authenticated());
        mvc.perform(formLogin().user("alex@example.com").password("correct-horse-battery"))
                .andExpect(unauthenticated());
    }

    @Test
    void disablingAnAccountCutsOffTheSessionItAlreadyHolds() throws Exception {
        // the principal is snapshotted here, while Sam is still enabled
        RequestPostProcessor samsOpenSession = as(sam);
        mvc.perform(get("/keys").with(samsOpenSession)).andExpect(status().isOk());

        mvc.perform(post("/people/{id}", sam.getId()).with(as(alex)).with(csrf())
                        .param("role", "MEMBER")
                        .param("groupId", alex.getGroup().getId().toString())
                        .param("enabled", "false"))
                .andExpect(redirectedUrl("/people/" + sam.getId()));

        // revoking access has to take effect now, not at their next sign-in
        mvc.perform(get("/keys").with(samsOpenSession)).andExpect(status().isForbidden());
        mvc.perform(formLogin().user("sam@example.com").password("correct-horse-battery"))
                .andExpect(unauthenticated());
    }

    @Test
    void aGroupAdminCannotDemoteTheSuperAdmin() throws Exception {
        UserAccount superAdmin = accounts.findByEmailIgnoreCase("admin@example.com").orElseThrow();
        // put the super admin in Alex's group, so only the role check can stop Alex
        superAdmin.setGroup(alex.getGroup());
        accounts.save(superAdmin);

        mvc.perform(post("/people/{id}", superAdmin.getId()).with(as(alex)).with(csrf())
                        .param("role", "MEMBER")
                        .param("groupId", alex.getGroup().getId().toString())
                        .param("enabled", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Not an administrator of this account")));

        assertThat(accounts.findById(superAdmin.getId()).orElseThrow().getRole())
                .isEqualTo(UserRole.SUPER_ADMIN);
    }

    @Test
    void aGroupAdminCanPromoteAMemberButNotMoveThemOut() throws Exception {
        mvc.perform(post("/people/{id}", sam.getId()).with(as(alex)).with(csrf())
                        .param("role", "GROUP_ADMIN")
                        .param("groupId", alex.getGroup().getId().toString())
                        .param("enabled", "true"))
                .andExpect(redirectedUrl("/people/" + sam.getId()));
        assertThat(accounts.findById(sam.getId()).orElseThrow().getRole()).isEqualTo(UserRole.GROUP_ADMIN);

        mvc.perform(post("/people/{id}", sam.getId()).with(as(alex)).with(csrf())
                        .param("role", "MEMBER")
                        .param("groupId", jo.getGroup().getId().toString())
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Only a super admin can move an account")));
    }
}
