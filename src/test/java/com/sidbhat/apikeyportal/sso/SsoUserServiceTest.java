package com.sidbhat.apikeyportal.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;

import com.sidbhat.apikeyportal.activity.ActivityService;
import com.sidbhat.apikeyportal.user.TestUsers;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountRepository;
import com.sidbhat.apikeyportal.user.UserGroup;
import com.sidbhat.apikeyportal.user.UserRole;

/**
 * The rules that decide whether somebody arriving from the identity provider
 * gets into the portal, and as what.
 * <p>
 * {@code loadUser} itself talks to the provider, so these drive the mapping step
 * underneath it directly - the part that holds all the decisions.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SsoUserServiceTest {

    private static final String SUBJECT = "00000000-1111-2222-3333-444444444444";

    @Mock
    private SsoService ssoService;

    @Mock
    private UserAccountRepository accounts;

    @Mock
    private ActivityService activity;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private SsoUserService service;
    private SsoConfiguration config;
    private final UserGroup platformTeam = TestUsers.platformTeam();

    @BeforeEach
    void setUp() {
        service = new SsoUserService(ssoService, accounts, activity, passwordEncoder);
        config = new SsoConfiguration("Contoso", "https://issuer.example.com", "client", "secret");
        config.setEnabled(true);
        config.setRequiredGroup("portal-users");
        config.setDefaultGroup(platformTeam);
        ReflectionTestUtils.setField(config, "id", 1L);
        when(ssoService.find()).thenReturn(Optional.of(config));
        when(accounts.save(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Drives the mapping the way {@code loadUser} does once the provider has replied. */
    private UserAccount signIn(Map<String, Object> claims) {
        OidcUser oidcUser = oidcUser(claims);
        return service.resolve(oidcUser.getSubject(), UserAccount.normalizeEmail(oidcUser.getEmail()),
                oidcUser.getFullName(), SsoUserService.groupsFrom(claims, config), config);
    }

    private void checkMembership(Map<String, Object> claims) {
        service.requireMembership(claims, config, SUBJECT);
    }

    private static OidcUser oidcUser(Map<String, Object> claims) {
        Map<String, Object> all = new HashMap<>(claims);
        all.putIfAbsent("sub", SUBJECT);
        return new DefaultOidcUser(List.of(), new OidcIdToken("token", Instant.now(),
                Instant.now().plusSeconds(300), all));
    }

    private static Map<String, Object> claims(Object groups) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", SUBJECT);
        claims.put("email", "sam@contoso.com");
        claims.put("name", "Sam Diaz");
        if (groups != null) {
            claims.put("groups", groups);
        }
        return claims;
    }

    // ---------- the access gate ----------

    @Test
    void membershipOfTheRequiredGroupLetsSomebodyIn() {
        checkMembership(claims(List.of("other-group", "portal-users")));
    }

    @Test
    void somebodyWithoutTheGroupIsRefused() {
        assertThatThrownBy(() -> checkMembership(claims(List.of("some-other-group"))))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("isn't a member of the group");
    }

    @Test
    void somebodyWithNoGroupsClaimAtAllIsRefused() {
        assertThatThrownBy(() -> checkMembership(claims(null)))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void groupMatchingIgnoresCaseAndSurroundingSpace() {
        config.setRequiredGroup("  Portal-Users  ");

        checkMembership(claims(List.of("PORTAL-USERS")));
    }

    @Test
    void aSingleGroupSentAsABareStringStillMatches() {
        // providers send a string rather than a list when there is exactly one
        checkMembership(claims("portal-users"));
    }

    @Test
    void anEntraGroupOverageIsReportedAsItsOwnProblem() {
        // too many groups to fit in the token: the claim is replaced by a pointer.
        // Reporting this as "not in the group" would send an admin hunting.
        Map<String, Object> claims = claims(null);
        claims.put("_claim_names", Map.of("groups", "src1"));

        assertThatThrownBy(() -> checkMembership(claims))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("too many groups");
    }

    // ---------- provisioning ----------

    @Test
    void afirstSignInCreatesAnAccountInTheConfiguredGroup() {
        when(accounts.findBySsoSubject(SUBJECT)).thenReturn(Optional.empty());
        when(accounts.findByEmailIgnoreCase("sam@contoso.com")).thenReturn(Optional.empty());

        UserAccount account = signIn(claims(List.of("portal-users")));

        assertThat(account.getEmail()).isEqualTo("sam@contoso.com");
        assertThat(account.getDisplayName()).isEqualTo("Sam Diaz");
        assertThat(account.getRole()).isEqualTo(UserRole.MEMBER);
        assertThat(account.getGroup()).isEqualTo(platformTeam);
        assertThat(account.getSsoSubject()).isEqualTo(SUBJECT);
        assertThat(account.isSsoLinked()).isTrue();
    }

    @Test
    void anSsoAccountGetsAPasswordNobodyCanUse() {
        when(accounts.findBySsoSubject(SUBJECT)).thenReturn(Optional.empty());
        when(accounts.findByEmailIgnoreCase("sam@contoso.com")).thenReturn(Optional.empty());

        UserAccount account = signIn(claims(List.of("portal-users")));

        assertThat(account.getPasswordHash()).startsWith("$2");
        assertThat(passwordEncoder.matches("", account.getPasswordHash())).isFalse();
        assertThat(passwordEncoder.matches(SUBJECT, account.getPasswordHash())).isFalse();
        assertThat(passwordEncoder.matches("sam@contoso.com", account.getPasswordHash())).isFalse();
    }

    @Test
    void anExistingInvitedAccountIsLinkedRatherThanDuplicated() {
        UserAccount existing = TestUsers.account(5L, "sam@contoso.com", "Sam Diaz",
                UserRole.GROUP_ADMIN, platformTeam);
        when(accounts.findBySsoSubject(SUBJECT)).thenReturn(Optional.empty());
        when(accounts.findByEmailIgnoreCase("sam@contoso.com")).thenReturn(Optional.of(existing));

        UserAccount account = signIn(claims(List.of("portal-users")));

        assertThat(account).isSameAs(existing);
        assertThat(account.getSsoSubject()).isEqualTo(SUBJECT);
        // their existing standing in the portal is untouched
        assertThat(account.getRole()).isEqualTo(UserRole.GROUP_ADMIN);
    }

    @Test
    void aReturningUserIsFoundBySubjectEvenAfterTheirEmailChanges() {
        UserAccount existing = TestUsers.account(5L, "old@contoso.com", "Sam Diaz",
                UserRole.MEMBER, platformTeam);
        existing.setSsoSubject(SUBJECT);
        when(accounts.findBySsoSubject(SUBJECT)).thenReturn(Optional.of(existing));

        UserAccount account = signIn(claims(List.of("portal-users")));

        assertThat(account.getEmail()).isEqualTo("sam@contoso.com");
        verify(accounts, never()).findByEmailIgnoreCase(any());
    }

    @Test
    void aDisabledAccountStaysLockedOut() {
        UserAccount disabled = TestUsers.account(5L, "sam@contoso.com", "Sam Diaz",
                UserRole.MEMBER, platformTeam);
        disabled.setSsoSubject(SUBJECT);
        disabled.setEnabled(false);
        when(accounts.findBySsoSubject(SUBJECT)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> signIn(claims(List.of("portal-users"))))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("disabled");
    }

    // ---------- role mapping ----------

    @Test
    void theAdminGroupGrantsGroupAdminOnCreation() {
        config.setAdminGroup("portal-admins");
        when(accounts.findBySsoSubject(SUBJECT)).thenReturn(Optional.empty());
        when(accounts.findByEmailIgnoreCase("sam@contoso.com")).thenReturn(Optional.empty());

        UserAccount account = signIn(claims(List.of("portal-users", "portal-admins")));

        assertThat(account.getRole()).isEqualTo(UserRole.GROUP_ADMIN);
    }

    @Test
    void droppingOutOfTheAdminGroupDemotesOnTheNextSignIn() {
        config.setAdminGroup("portal-admins");
        UserAccount existing = TestUsers.account(5L, "sam@contoso.com", "Sam Diaz",
                UserRole.GROUP_ADMIN, platformTeam);
        existing.setSsoSubject(SUBJECT);
        when(accounts.findBySsoSubject(SUBJECT)).thenReturn(Optional.of(existing));

        UserAccount account = signIn(claims(List.of("portal-users")));

        assertThat(account.getRole()).isEqualTo(UserRole.MEMBER);
    }

    @Test
    void withoutAnAdminGroupTheProviderDoesNotTouchRolesAtAll() {
        config.setAdminGroup(null);
        UserAccount existing = TestUsers.account(5L, "sam@contoso.com", "Sam Diaz",
                UserRole.GROUP_ADMIN, platformTeam);
        existing.setSsoSubject(SUBJECT);
        when(accounts.findBySsoSubject(SUBJECT)).thenReturn(Optional.of(existing));

        assertThat(signIn(claims(List.of("portal-users"))).getRole()).isEqualTo(UserRole.GROUP_ADMIN);
    }

    @Test
    void aSuperAdminIsNeverDemotedByTheDirectory() {
        // the one role that administers every group must not be in the IdP's gift,
        // in either direction
        config.setAdminGroup("portal-admins");
        UserAccount superAdmin = TestUsers.account(1L, "sam@contoso.com", "The Boss",
                UserRole.SUPER_ADMIN, platformTeam);
        superAdmin.setSsoSubject(SUBJECT);
        when(accounts.findBySsoSubject(SUBJECT)).thenReturn(Optional.of(superAdmin));

        assertThat(signIn(claims(List.of("portal-users"))).getRole()).isEqualTo(UserRole.SUPER_ADMIN);
    }

    @Test
    void noClaimCanEverGrantSuperAdmin() {
        config.setAdminGroup("portal-admins");
        when(accounts.findBySsoSubject(SUBJECT)).thenReturn(Optional.empty());
        when(accounts.findByEmailIgnoreCase("sam@contoso.com")).thenReturn(Optional.empty());

        UserAccount account = signIn(claims(List.of("portal-users", "portal-admins", "SUPER_ADMIN",
                "super-admins", "administrators")));

        assertThat(account.getRole()).isNotEqualTo(UserRole.SUPER_ADMIN);
    }

    @Test
    void aNewSsoAccountIsNotPinnedToAPasswordChange() {
        when(accounts.findBySsoSubject(SUBJECT)).thenReturn(Optional.empty());
        when(accounts.findByEmailIgnoreCase("sam@contoso.com")).thenReturn(Optional.empty());

        // there is no handed-out password behind it, so the pin would just strand them
        assertThat(signIn(claims(List.of("portal-users"))).isMustChangePassword()).isFalse();
    }

    @Test
    void signingInThroughTheProviderDoesNotLiftAnAdministratorsForcedPasswordChange() {
        UserAccount existing = TestUsers.account(5L, "sam@contoso.com", "Sam Diaz",
                UserRole.MEMBER, platformTeam);
        existing.setSsoSubject(SUBJECT);
        existing.setMustChangePassword(true);
        when(accounts.findBySsoSubject(SUBJECT)).thenReturn(Optional.of(existing));

        assertThat(signIn(claims(List.of("portal-users"))).isMustChangePassword()).isTrue();
    }

    // ---------- linking is not a way to take an account over ----------

    @Test
    void anAccountAlreadyLinkedToAnotherSubjectIsNotRePointed() {
        // a second provider identity presenting the same email would otherwise
        // walk straight into the first one's account
        UserAccount linkedToSomebodyElse = TestUsers.account(5L, "sam@contoso.com", "Sam Diaz",
                UserRole.MEMBER, platformTeam);
        linkedToSomebodyElse.setSsoSubject("a-different-subject");
        when(accounts.findBySsoSubject(SUBJECT)).thenReturn(Optional.empty());
        when(accounts.findByEmailIgnoreCase("sam@contoso.com")).thenReturn(Optional.of(linkedToSomebodyElse));

        assertThatThrownBy(() -> signIn(claims(List.of("portal-users"))))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("already linked");
        assertThat(linkedToSomebodyElse.getSsoSubject()).isEqualTo("a-different-subject");
    }

    @Test
    void theSuperAdminAccountIsNeverAdoptedByTheProvider() {
        UserAccount superAdmin = TestUsers.account(1L, "admin@contoso.com", "The Boss",
                UserRole.SUPER_ADMIN, platformTeam);
        when(accounts.findBySsoSubject(SUBJECT)).thenReturn(Optional.empty());
        when(accounts.findByEmailIgnoreCase("admin@contoso.com")).thenReturn(Optional.of(superAdmin));

        assertThatThrownBy(() -> service.resolve(SUBJECT, "admin@contoso.com", "The Boss",
                java.util.Set.of("portal-users"), config))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(superAdmin.getSsoSubject()).isNull();
    }
}
