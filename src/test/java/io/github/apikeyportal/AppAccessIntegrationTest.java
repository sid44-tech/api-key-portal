package io.github.apikeyportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import io.github.apikeyportal.user.PortalUser;

import io.github.apikeyportal.activity.ActivityAction;
import io.github.apikeyportal.activity.ActivityEventRepository;
import io.github.apikeyportal.appaccess.AppAccessService;
import io.github.apikeyportal.appaccess.AppAccessToken;
import io.github.apikeyportal.appaccess.AppAccessTokenRepository;
import io.github.apikeyportal.appaccess.IssuedAppToken;
import io.github.apikeyportal.key.ApiKey;
import io.github.apikeyportal.key.ApiKeyRepository;
import io.github.apikeyportal.key.ApiKeyService;
import io.github.apikeyportal.user.UserAccount;
import io.github.apikeyportal.user.UserAccountRepository;
import io.github.apikeyportal.user.UserGroup;
import io.github.apikeyportal.user.UserGroupRepository;
import io.github.apikeyportal.user.UserRole;

/**
 * Application read access end to end: the server-wide switch, who may manage
 * tokens, and what an app holding one can and cannot read.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AppAccessIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppAccessService appAccess;

    @Autowired
    private AppAccessTokenRepository tokens;

    @Autowired
    private ApiKeyRepository keys;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private UserGroupRepository groups;

    @Autowired
    private ActivityEventRepository activity;

    private UserGroup platformTeam;
    private UserAccount superAdmin;
    private UserAccount alex;
    private UserAccount sam;
    private UserAccount jo;
    private ApiKey stripeKey;
    private ApiKey otherGroupKey;

    @BeforeEach
    void setUp() {
        platformTeam = groups.save(new UserGroup("Platform team"));
        UserGroup okafors = groups.save(new UserGroup("The Okafors"));
        superAdmin = accounts.findAllByOrderByDisplayNameAsc().stream()
                .filter(UserAccount::isSuperAdmin).findFirst().orElseThrow();
        alex = accounts.save(new UserAccount("alex@example.com", "Alex Carter", "x", UserRole.GROUP_ADMIN,
                platformTeam));
        sam = accounts.save(new UserAccount("sam@example.com", "Sam Diaz", "x", UserRole.MEMBER, platformTeam));
        jo = accounts.save(new UserAccount("jo@example.com", "Jo Okafor", "x", UserRole.GROUP_ADMIN, okafors));
        stripeKey = addKey("Prod secret", "sk_live_original", sam, platformTeam);
        otherGroupKey = addKey("Their key", "theirs", jo, okafors);
    }

    @AfterEach
    void cleanUp() {
        appAccess.setEnabled(false, superAdmin);
        tokens.deleteAll();
        keys.deleteAll();
        accounts.deleteAll(List.of(alex, sam, jo));
        groups.deleteAll(groups.findAllByOrderByNameAsc().stream()
                .filter(g -> !"Test group".equals(g.getName())).toList());
    }

    private ApiKey addKey(String name, String value, UserAccount owner, UserGroup group) {
        ApiKey key = new ApiKey(name, "Stripe", value, LocalDate.now().minusDays(10), LocalDate.now().plusDays(80));
        key.assignTo(owner, group);
        return keys.save(key);
    }

    private IssuedAppToken tokenForStripe(UserAccount creator) {
        return appAccess.create("billing-service", platformTeam.getId(), List.of(stripeKey.getId()), 30, creator);
    }

    private Long refOf(IssuedAppToken issued) {
        return tokens.findById(issued.token().getId()).orElseThrow().getGrants().get(0).getId();
    }

    private ResultActions read(String token, Long ref) throws Exception {
        return mvc.perform(get("/api/v1/keys/" + ref).header("Authorization", "Bearer " + token));
    }

    // ---------- the switch ----------

    @Test
    void theSwitchStartsOffAndOnlyASuperAdminCanFlipIt() {
        assertThat(appAccess.isEnabled()).isFalse();

        assertThatThrownBy(() -> appAccess.setEnabled(true, alex)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appAccess.setEnabled(true, sam)).isInstanceOf(AccessDeniedException.class);
        assertThat(appAccess.isEnabled()).isFalse();

        appAccess.setEnabled(true, superAdmin);
        assertThat(appAccess.isEnabled()).isTrue();
        assertThat(appAccess.getSetting().getUpdatedByEmail()).isEqualTo(superAdmin.getEmail());
    }

    @Test
    void withTheSwitchOffNoTokenCanBeCreatedOrUsedButOneCanStillBeDeleted() throws Exception {
        assertThatThrownBy(() -> tokenForStripe(alex)).isInstanceOf(IllegalStateException.class);

        appAccess.setEnabled(true, superAdmin);
        IssuedAppToken issued = tokenForStripe(alex);
        appAccess.setEnabled(false, superAdmin);

        read(issued.plaintext(), refOf(issued))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("api_access_disabled"))
                .andExpect(content().string(not(containsString("sk_live"))));

        // taking access away is never blocked
        appAccess.delete(issued.token().getId(), alex);
        assertThat(tokens.count()).isZero();
    }

    // ---------- who manages tokens ----------

    @Test
    void groupAdminsAndSuperAdminsManageTokensMembersOnlySeeThem() {
        appAccess.setEnabled(true, superAdmin);

        assertThatThrownBy(() -> tokenForStripe(sam)).isInstanceOf(AccessDeniedException.class);
        // an admin of another group is a member as far as this group is concerned
        assertThatThrownBy(() -> tokenForStripe(jo)).isInstanceOf(AccessDeniedException.class);

        IssuedAppToken byAlex = tokenForStripe(alex);
        IssuedAppToken bySuperAdmin = tokenForStripe(superAdmin);

        // Sam sees both exist - read-only, and the list carries no secret to leak
        assertThat(appAccess.findVisibleTo(sam)).extracting(AppAccessToken::getId)
                .containsExactlyInAnyOrder(byAlex.token().getId(), bySuperAdmin.token().getId());
        assertThat(appAccess.findVisibleTo(jo)).isEmpty();
        assertThatThrownBy(() -> appAccess.delete(byAlex.token().getId(), sam))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appAccess.delete(byAlex.token().getId(), jo))
                .isInstanceOf(AccessDeniedException.class);

        appAccess.delete(bySuperAdmin.token().getId(), alex);
        appAccess.delete(byAlex.token().getId(), superAdmin);
        assertThat(tokens.count()).isZero();
    }

    @Test
    void aTokenCanOnlyCoverKeysOfItsOwnGroup() {
        appAccess.setEnabled(true, superAdmin);

        assertThatThrownBy(() -> appAccess.create("sneaky", platformTeam.getId(),
                List.of(stripeKey.getId(), otherGroupKey.getId()), 30, alex))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to Platform team");
        assertThatThrownBy(() -> appAccess.create("forever", platformTeam.getId(),
                List.of(stripeKey.getId()), 366, alex))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tokens.count()).isZero();
    }

    @Test
    void onlyAHashOfTheTokenIsStored() {
        appAccess.setEnabled(true, superAdmin);
        IssuedAppToken issued = tokenForStripe(alex);

        AppAccessToken stored = tokens.findById(issued.token().getId()).orElseThrow();
        assertThat(issued.plaintext()).startsWith("kpa_").hasSize(47);
        assertThat(stored.getTokenPrefix()).isEqualTo(issued.plaintext().substring(0, 12));
        assertThat((String) ReflectionTestUtils.getField(stored, "tokenHash"))
                .hasSize(64).doesNotContain(issued.plaintext().substring(4));
    }

    // ---------- the read API ----------

    @Test
    void anAppListsItsKeysWithoutValuesAndReadsOneWithItsToken() throws Exception {
        appAccess.setEnabled(true, superAdmin);
        IssuedAppToken issued = tokenForStripe(alex);
        Long ref = refOf(issued);

        mvc.perform(get("/api/v1/keys").header("Authorization", "Bearer " + issued.plaintext()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].ref").value(ref))
                .andExpect(jsonPath("$.keys[0].name").value("Prod secret"))
                .andExpect(content().string(not(containsString("sk_live"))));

        read(issued.plaintext(), ref)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.value").value("sk_live_original"))
                .andExpect(jsonPath("$.keyId").value(stripeKey.getId()));
        read(issued.plaintext(), ref).andExpect(status().isOk());

        // one history entry for the value the app picked up, not one per poll
        String actor = "app-token:" + issued.plaintext().substring(0, 12);
        assertThat(activity.findAll()).filteredOn(e -> e.getAction() == ActivityAction.KEY_READ_BY_APP
                        && actor.equals(e.getActorEmail()))
                .hasSize(1)
                .allSatisfy(e -> assertThat(e.getActorName()).isEqualTo("App token: billing-service"));
        assertThat(tokens.findById(issued.token().getId()).orElseThrow().getLastUsedAt()).isNotNull();
    }

    @Test
    void aRotatedKeyReachesTheAppThroughTheSameReference() throws Exception {
        appAccess.setEnabled(true, superAdmin);
        IssuedAppToken issued = tokenForStripe(alex);
        Long ref = refOf(issued);
        read(issued.plaintext(), ref).andExpect(jsonPath("$.value").value("sk_live_original"));

        ApiKey replacement = addKey("Prod secret", "sk_live_replacement", sam, platformTeam);
        replacement.recordProvenance(null, null, null, stripeKey.getId());
        replacement = keys.save(replacement);

        read(issued.plaintext(), ref)
                .andExpect(jsonPath("$.value").value("sk_live_replacement"))
                .andExpect(jsonPath("$.keyId").value(replacement.getId()));

        // deleting the old key doesn't break the reference either
        apiKeyService.delete(stripeKey.getId(), accounts.findById(sam.getId()).orElseThrow());
        read(issued.plaintext(), ref).andExpect(jsonPath("$.value").value("sk_live_replacement"));
    }

    @Test
    void deletingAKeyWithNoReplacementTakesItAwayFromTheToken() throws Exception {
        appAccess.setEnabled(true, superAdmin);
        IssuedAppToken issued = tokenForStripe(alex);
        Long ref = refOf(issued);

        apiKeyService.delete(stripeKey.getId(), accounts.findById(sam.getId()).orElseThrow());

        read(issued.plaintext(), ref).andExpect(status().isNotFound());
    }

    @Test
    void missingUnknownExpiredAndDeletedTokensAreAllRefused() throws Exception {
        appAccess.setEnabled(true, superAdmin);
        IssuedAppToken issued = tokenForStripe(alex);
        Long ref = refOf(issued);

        mvc.perform(get("/api/v1/keys/" + ref))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")));
        read("not-a-token", ref).andExpect(status().isUnauthorized());
        read("kpa_" + "A".repeat(43), ref).andExpect(status().isUnauthorized());
        read(issued.plaintext(), ref + 999).andExpect(status().isNotFound());

        // a signed-in browser session is not a token
        mvc.perform(get("/api/v1/keys/" + ref).with(
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(
                        new io.github.apikeyportal.user.PortalUser(sam))))
                .andExpect(status().isUnauthorized());

        AppAccessToken stored = tokens.findById(issued.token().getId()).orElseThrow();
        ReflectionTestUtils.setField(stored, "expiresAt", Instant.now().minusSeconds(1));
        tokens.save(stored);
        read(issued.plaintext(), ref).andExpect(status().isUnauthorized());

        appAccess.delete(issued.token().getId(), alex);
        read(issued.plaintext(), ref).andExpect(status().isUnauthorized());
    }

    @Test
    void theApiTakesNoFormPostsOrSessions() throws Exception {
        appAccess.setEnabled(true, superAdmin);
        // nothing here changes state, and there is no POST to forge
        mvc.perform(post("/api/v1/keys")).andExpect(status().is4xxClientError());
    }

    // ---------- the pages ----------

    private RequestPostProcessor as(UserAccount account) {
        return user(new PortalUser(accounts.findById(account.getId()).orElseThrow()));
    }

    @Test
    void onlyASuperAdminSeesAndFlipsTheSwitch() throws Exception {
        mvc.perform(get("/app-access").with(as(alex)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Switch on"))));
        mvc.perform(post("/app-access/switch").with(as(alex)).with(csrf()).param("enabled", "true"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/app-access/switch").with(as(sam)).with(csrf()).param("enabled", "true"))
                .andExpect(status().isForbidden());
        assertThat(appAccess.isEnabled()).isFalse();

        mvc.perform(get("/app-access").with(as(superAdmin)))
                .andExpect(content().string(containsString("Switch on")));
        mvc.perform(post("/app-access/switch").with(as(superAdmin)).with(csrf()).param("enabled", "true"))
                .andExpect(redirectedUrl("/app-access"));
        assertThat(appAccess.isEnabled()).isTrue();
    }

    @Test
    void theNavigationOffersAppAccessToMembersOnlyWhileItIsOn() throws Exception {
        mvc.perform(get("/keys").with(as(sam)))
                .andExpect(content().string(not(containsString("href=\"/app-access\""))));
        mvc.perform(get("/keys").with(as(superAdmin)))
                .andExpect(content().string(containsString("href=\"/app-access\"")));

        appAccess.setEnabled(true, superAdmin);
        mvc.perform(get("/keys").with(as(sam)))
                .andExpect(content().string(containsString("href=\"/app-access\"")));
    }

    @Test
    void aGroupAdminCreatesATokenThroughTheFormAndSeesItOnce() throws Exception {
        mvc.perform(get("/app-access/new").with(as(alex)))
                .andExpect(redirectedUrl("/app-access"));

        appAccess.setEnabled(true, superAdmin);
        mvc.perform(get("/app-access/new").with(as(alex)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Prod secret")))
                // another group's key is never offered
                .andExpect(content().string(not(containsString("Their key"))));

        mvc.perform(post("/app-access/new").with(as(alex)).with(csrf())
                        .param("name", "billing-service")
                        .param("groupId", platformTeam.getId().toString())
                        .param("validDays", "90")
                        .param("keyIds", stripeKey.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(view().name("app-access/created"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(content().string(containsString("kpa_")))
                .andExpect(content().string(not(containsString("sk_live"))));
        assertThat(tokens.count()).isEqualTo(1);

        // the list never shows the token again, only its first characters
        String prefix = tokens.findAll().get(0).getTokenPrefix();
        mvc.perform(get("/app-access").with(as(alex)))
                .andExpect(content().string(containsString("billing-service")))
                .andExpect(content().string(containsString(prefix + "…")));
    }

    @Test
    void aMemberSeesTokensReadOnlyAndCannotChangeAnything() throws Exception {
        appAccess.setEnabled(true, superAdmin);
        IssuedAppToken issued = tokenForStripe(alex);

        mvc.perform(get("/app-access").with(as(sam)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("billing-service")))
                .andExpect(content().string(containsString("Group admins only")))
                .andExpect(content().string(not(containsString("New app token"))))
                .andExpect(content().string(not(containsString(issued.plaintext()))))
                .andExpect(content().string(not(containsString("sk_live"))));

        mvc.perform(get("/app-access/new").with(as(sam))).andExpect(status().isForbidden());
        mvc.perform(post("/app-access/new").with(as(sam)).with(csrf())
                        .param("name", "mine").param("groupId", platformTeam.getId().toString())
                        .param("validDays", "30").param("keyIds", stripeKey.getId().toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/app-access/tokens/" + issued.token().getId() + "/delete").with(as(sam)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test
    void anAdminOfAnotherGroupCanNeitherSeeNorTouchTheTokens() throws Exception {
        appAccess.setEnabled(true, superAdmin);
        IssuedAppToken issued = tokenForStripe(alex);

        mvc.perform(get("/app-access").with(as(jo)))
                .andExpect(content().string(not(containsString("billing-service"))));
        mvc.perform(post("/app-access/new").with(as(jo)).with(csrf())
                        .param("name", "sneaky").param("groupId", platformTeam.getId().toString())
                        .param("validDays", "30").param("keyIds", stripeKey.getId().toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/app-access/tokens/" + issued.token().getId() + "/delete").with(as(jo)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test
    void aGroupAdminCanDeleteATokenFromThePageEvenWithTheSwitchOff() throws Exception {
        appAccess.setEnabled(true, superAdmin);
        IssuedAppToken issued = tokenForStripe(alex);
        appAccess.setEnabled(false, superAdmin);

        mvc.perform(post("/app-access/tokens/" + issued.token().getId() + "/delete").with(as(alex)).with(csrf()))
                .andExpect(redirectedUrl("/app-access"));
        assertThat(tokens.count()).isZero();
    }
}
