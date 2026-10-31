package com.sidbhat.apikeyportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.sidbhat.apikeyportal.invite.UserInvitation;
import com.sidbhat.apikeyportal.invite.UserInvitationRepository;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountRepository;
import com.sidbhat.apikeyportal.user.UserGroup;
import com.sidbhat.apikeyportal.user.UserGroupRepository;
import com.sidbhat.apikeyportal.user.UserRole;

/**
 * Invitations end to end: an admin invites an address, the link in the email
 * creates exactly the account that was described, and it stops working after a
 * day. Also the other half of a closed portal - the seeded super admin can't do
 * anything until the default password is gone.
 * <p>
 * No mail server is configured under test, so the portal hands the link back to
 * the inviting admin instead of sending it. That is the same link it would have
 * emailed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InvitationIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private UserGroupRepository groups;

    @Autowired
    private UserInvitationRepository invitations;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UserAccount superAdmin;
    private UserAccount alex;
    private UserGroup platformTeam;
    private UserGroup okafors;

    @BeforeEach
    void setUpTwoGroups() {
        superAdmin = accounts.findByEmailIgnoreCase("admin@example.com").orElseThrow();
        platformTeam = groups.save(new UserGroup("Platform team"));
        okafors = groups.save(new UserGroup("The Okafors"));
        alex = accounts.save(new UserAccount("alex@example.com", "Alex Carter",
                passwordEncoder.encode("correct-horse-battery"), UserRole.GROUP_ADMIN, platformTeam));
    }

    @AfterEach
    void cleanUp() {
        invitations.deleteAll();
        accounts.deleteAll(accounts.findAllByOrderByDisplayNameAsc().stream()
                .filter(a -> !a.is(superAdmin))
                .toList());
        groups.deleteAll(groups.findAllByOrderByNameAsc().stream()
                .filter(g -> !"Test group".equals(g.getName()))
                .toList());
    }

    private RequestPostProcessor as(UserAccount account) {
        return user(new PortalUser(accounts.findById(account.getId()).orElseThrow()));
    }

    /** Invites someone and returns the link that would have been emailed. */
    private String inviteAndCaptureLink(UserAccount inviter, String email, UserRole role, UserGroup group)
            throws Exception {
        MvcResult result = mvc.perform(post("/invitations").with(as(inviter)).with(csrf())
                        .param("email", email)
                        .param("role", role.name())
                        .param("groupId", group.getId().toString()))
                .andExpect(redirectedUrl("/invitations"))
                .andReturn();
        Object link = result.getFlashMap().get("invitationLink");
        assertThat(link).as("the invitation link, since no mail server is configured").isNotNull();
        return link.toString();
    }

    /** Turns an absolute invitation link into the path MockMvc can request. */
    private static String pathOf(String link) {
        return link.substring(link.indexOf("/invite/"));
    }

    @Test
    void anInvitedUserEndsUpWithExactlyTheAccountTheyWereOffered() throws Exception {
        String link = inviteAndCaptureLink(alex, "sam@example.com", UserRole.MEMBER, platformTeam);

        mvc.perform(get(pathOf(link)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("sam@example.com")))
                .andExpect(content().string(containsString("Platform team")));

        mvc.perform(post(pathOf(link)).with(csrf())
                        .param("displayName", "Sam Diaz")
                        .param("password", "a-password-of-their-own")
                        .param("confirmPassword", "a-password-of-their-own"))
                .andExpect(redirectedUrl("/login?registered"));

        UserAccount sam = accounts.findByEmailIgnoreCase("sam@example.com").orElseThrow();
        assertThat(sam.getDisplayName()).isEqualTo("Sam Diaz");
        assertThat(sam.getRole()).isEqualTo(UserRole.MEMBER);
        assertThat(sam.getGroup().getName()).isEqualTo("Platform team");
        assertThat(sam.getPasswordHash()).startsWith("$2").doesNotContain("a-password-of-their-own");

        mvc.perform(formLogin().user("sam@example.com").password("a-password-of-their-own"))
                .andExpect(authenticated());
    }

    /**
     * The whole point of the invitation flow is that it ends with the person
     * signed in. It used to take two attempts: {@code /login?registered} was not
     * covered by {@code formLogin().permitAll()}, which matches the login URL
     * exactly including its query string, so the page was treated as protected.
     * The redirect to sign in stashed it as the saved request, and the first
     * successful sign-in went there - back to the login form - instead of the
     * portal.
     */
    @Test
    void anInvitedUserIsSignedInOnTheirFirstAttempt() throws Exception {
        String link = inviteAndCaptureLink(alex, "sam@example.com", UserRole.MEMBER, platformTeam);

        // one browser session across accepting, landing on the login page and signing in
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post(pathOf(link)).session(session).with(csrf())
                        .param("displayName", "Sam Diaz")
                        .param("password", "a-password-of-their-own")
                        .param("confirmPassword", "a-password-of-their-own"))
                .andExpect(redirectedUrl("/login?registered"));

        // the query string has to be literal: MockMvc's .param() leaves getQueryString()
        // null, which is exactly the case the exact-URL matcher did allow through
        mvc.perform(get("/login?registered").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Your account is ready")));

        mvc.perform(post("/login").session(session).with(csrf())
                        .param("username", "sam@example.com")
                        .param("password", "a-password-of-their-own"))
                .andExpect(authenticated())
                .andExpect(redirectedUrl("/keys"));
    }

    @Test
    void theOtherLoginNoticesAreReachableToo() throws Exception {
        // same exact-URL trap as ?registered, so it is worth pinning down
        mvc.perform(get("/login?passwordChanged"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Password changed")));
        mvc.perform(get("/login?logout"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("signed out")));
    }

    @Test
    void theSameLinkCannotBeUsedTwice() throws Exception {
        String link = inviteAndCaptureLink(alex, "sam@example.com", UserRole.MEMBER, platformTeam);
        mvc.perform(post(pathOf(link)).with(csrf())
                        .param("displayName", "Sam Diaz")
                        .param("password", "a-password-of-their-own")
                        .param("confirmPassword", "a-password-of-their-own"))
                .andExpect(redirectedUrl("/login?registered"));

        mvc.perform(get(pathOf(link)))
                .andExpect(content().string(containsString("can't be used")));
        mvc.perform(post(pathOf(link)).with(csrf())
                        .param("displayName", "Impostor")
                        .param("password", "another-password-here")
                        .param("confirmPassword", "another-password-here"))
                .andExpect(content().string(containsString("can't be used")));

        assertThat(accounts.findAllByOrderByDisplayNameAsc())
                .extracting(UserAccount::getDisplayName)
                .doesNotContain("Impostor");
    }

    @Test
    void aLinkStopsWorkingAfterItsDayAndCanBeReplaced() throws Exception {
        String link = inviteAndCaptureLink(alex, "sam@example.com", UserRole.MEMBER, platformTeam);
        UserInvitation invitation = invitations.findAllByOrderByCreatedAtDesc().get(0);

        // wind the clock past the day rather than waiting for it
        ReflectionTestUtils.setField(invitation, "expiresAt", Instant.now().minus(1, ChronoUnit.MINUTES));
        invitations.save(invitation);

        mvc.perform(get(pathOf(link)))
                .andExpect(content().string(containsString("can't be used")));
        mvc.perform(post(pathOf(link)).with(csrf())
                        .param("displayName", "Sam Diaz")
                        .param("password", "a-password-of-their-own")
                        .param("confirmPassword", "a-password-of-their-own"))
                .andExpect(content().string(containsString("can't be used")));
        assertThat(accounts.findByEmailIgnoreCase("sam@example.com")).isEmpty();

        // the day has elapsed, so another one can go out
        MvcResult resent = mvc.perform(post("/invitations/{id}/resend", invitation.getId())
                        .with(as(alex)).with(csrf()))
                .andExpect(redirectedUrl("/invitations"))
                .andReturn();
        String freshLink = resent.getFlashMap().get("invitationLink").toString();

        assertThat(freshLink).isNotEqualTo(link);
        mvc.perform(post(pathOf(freshLink)).with(csrf())
                        .param("displayName", "Sam Diaz")
                        .param("password", "a-password-of-their-own")
                        .param("confirmPassword", "a-password-of-their-own"))
                .andExpect(redirectedUrl("/login?registered"));
        assertThat(accounts.findByEmailIgnoreCase("sam@example.com")).isPresent();
    }

    @Test
    void aWithdrawnInvitationStopsWorkingStraightAway() throws Exception {
        String link = inviteAndCaptureLink(alex, "sam@example.com", UserRole.MEMBER, platformTeam);
        UserInvitation invitation = invitations.findAllByOrderByCreatedAtDesc().get(0);

        mvc.perform(post("/invitations/{id}/revoke", invitation.getId()).with(as(alex)).with(csrf()))
                .andExpect(redirectedUrl("/invitations"));

        mvc.perform(get(pathOf(link))).andExpect(content().string(containsString("can't be used")));
        assertThat(accounts.findByEmailIgnoreCase("sam@example.com")).isEmpty();
    }

    @Test
    void aGroupAdminCannotInviteOutsideTheirGroupOrAboveTheirStation() throws Exception {
        mvc.perform(post("/invitations").with(as(alex)).with(csrf())
                        .param("email", "sam@example.com")
                        .param("role", "MEMBER")
                        .param("groupId", okafors.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("your own group")));

        mvc.perform(post("/invitations").with(as(alex)).with(csrf())
                        .param("email", "sam@example.com")
                        .param("role", "SUPER_ADMIN")
                        .param("groupId", platformTeam.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("super admin")));

        assertThat(invitations.findAllByOrderByCreatedAtDesc()).isEmpty();
    }

    @Test
    void aSuperAdminCanInviteIntoAnyGroup() throws Exception {
        String link = inviteAndCaptureLink(superAdmin, "jo@example.com", UserRole.GROUP_ADMIN, okafors);

        mvc.perform(post(pathOf(link)).with(csrf())
                        .param("displayName", "Jo Okafor")
                        .param("password", "a-password-of-their-own")
                        .param("confirmPassword", "a-password-of-their-own"))
                .andExpect(redirectedUrl("/login?registered"));

        UserAccount jo = accounts.findByEmailIgnoreCase("jo@example.com").orElseThrow();
        assertThat(jo.getRole()).isEqualTo(UserRole.GROUP_ADMIN);
        assertThat(jo.getGroup().getName()).isEqualTo("The Okafors");
    }

    @Test
    void anAddressThatAlreadyHasAnAccountIsNotInvitedAgain() throws Exception {
        mvc.perform(post("/invitations").with(as(alex)).with(csrf())
                        .param("email", "alex@example.com")
                        .param("role", "MEMBER")
                        .param("groupId", platformTeam.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("already has an account")));

        assertThat(invitations.findAllByOrderByCreatedAtDesc()).isEmpty();
    }

    @Test
    void aSecondInvitationIsRefusedWhileTheFirstStillWorks() throws Exception {
        inviteAndCaptureLink(alex, "sam@example.com", UserRole.MEMBER, platformTeam);

        mvc.perform(post("/invitations").with(as(alex)).with(csrf())
                        .param("email", "sam@example.com")
                        .param("role", "MEMBER")
                        .param("groupId", platformTeam.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("already has an invitation")));

        assertThat(invitations.findAllByOrderByCreatedAtDesc()).hasSize(1);
    }

    @Test
    void aGroupSomeoneIsInvitedIntoCannotBeDeletedOutFromUnderThem() throws Exception {
        String link = inviteAndCaptureLink(superAdmin, "jo@example.com", UserRole.MEMBER, okafors);

        mvc.perform(post("/groups/{id}/delete", okafors.getId()).with(as(superAdmin)).with(csrf()))
                .andExpect(redirectedUrl("/groups"));
        assertThat(groups.findById(okafors.getId())).isPresent();

        // and the link still resolves, rather than blowing up on a group that went away
        mvc.perform(get(pathOf(link))).andExpect(status().isOk());
    }

    @Test
    void anAccountOnADefaultPasswordCanReachNothingButTheChangePasswordPage() throws Exception {
        UserAccount fresh = accounts.save(newAdminOnTheDefaultPassword());

        mvc.perform(get("/keys").with(as(fresh))).andExpect(redirectedUrl("/profile/password"));
        mvc.perform(get("/people").with(as(fresh))).andExpect(redirectedUrl("/profile/password"));
        mvc.perform(get("/invitations").with(as(fresh))).andExpect(redirectedUrl("/profile/password"));
        mvc.perform(get("/profile").with(as(fresh))).andExpect(redirectedUrl("/profile/password"));

        mvc.perform(get("/profile/password").with(as(fresh)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nothing else is reachable until you have")));
    }

    @Test
    void replacingTheDefaultPasswordUnlocksTheRestOfThePortal() throws Exception {
        UserAccount fresh = accounts.save(newAdminOnTheDefaultPassword());

        mvc.perform(post("/profile/password").with(as(fresh)).with(csrf())
                        .param("currentPassword", "changeme")
                        .param("newPassword", "a-password-of-their-own")
                        .param("confirmPassword", "a-password-of-their-own"))
                .andExpect(redirectedUrl("/login?passwordChanged"));

        assertThat(accounts.findById(fresh.getId()).orElseThrow().isMustChangePassword()).isFalse();
        mvc.perform(formLogin().user("fresh-admin@example.com").password("changeme"))
                .andExpect(unauthenticated());
        mvc.perform(formLogin().user("fresh-admin@example.com").password("a-password-of-their-own"))
                .andExpect(authenticated());
        // the principal is rebuilt at sign-in, so the lock is gone with it
        mvc.perform(get("/keys").with(as(fresh))).andExpect(status().isOk());
    }

    @Test
    void theDefaultPasswordAloneDoesNotGetAnybodyIntoThePortal() throws Exception {
        UserAccount fresh = accounts.save(newAdminOnTheDefaultPassword());

        // signing in works - it has to, or they could never change it - but that is all
        mvc.perform(formLogin().user("fresh-admin@example.com").password("changeme"))
                .andExpect(authenticated());
        mvc.perform(get("/keys").with(as(fresh))).andExpect(redirectedUrl("/profile/password"));
    }

    private UserAccount newAdminOnTheDefaultPassword() {
        UserAccount account = new UserAccount("fresh-admin@example.com", "Administrator",
                passwordEncoder.encode("changeme"), UserRole.SUPER_ADMIN, platformTeam);
        account.setMustChangePassword(true);
        return account;
    }
}
