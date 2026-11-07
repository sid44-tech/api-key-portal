package io.github.apikeyportal.appaccess;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.apikeyportal.activity.ActivityAction;
import io.github.apikeyportal.activity.ActivityService;
import io.github.apikeyportal.key.ApiKey;
import io.github.apikeyportal.key.ApiKeyRepository;
import io.github.apikeyportal.logging.AuditLog;
import io.github.apikeyportal.user.UserAccount;
import io.github.apikeyportal.user.UserGroup;
import io.github.apikeyportal.user.UserGroupRepository;
import io.github.apikeyportal.user.UserRole;

/**
 * Application read access: the server-wide switch, the tokens, and the reads
 * made with them.
 * <p>
 * Who may do what:
 * <ul>
 * <li>Only a super admin may turn the feature on or off.</li>
 * <li>With it on, a group admin manages their own group's tokens; a super admin
 *     manages any group's. Members see their group's tokens and nothing more -
 *     no token, no key value, no changes.</li>
 * <li>With it off, the read API refuses everything and no token can be created.
 *     Deleting a token stays possible: taking access away is never blocked.</li>
 * </ul>
 */
@Service
public class AppAccessService {

    static final int MAX_VALIDITY_DAYS = 365;
    static final int MAX_KEYS_PER_TOKEN = 50;
    static final int MAX_NAME_LENGTH = 100;
    /** A rotation chain longer than this is broken data, not history. */
    private static final int MAX_CHAIN_LENGTH = 1000;

    private static final Logger log = LoggerFactory.getLogger(AppAccessService.class);

    private final ApiAccessSettingRepository settings;
    private final AppAccessTokenRepository tokens;
    private final AppAccessGrantRepository grants;
    private final ApiKeyRepository keys;
    private final UserGroupRepository groups;
    private final ActivityService activity;
    private final AuditLog audit;

    public AppAccessService(ApiAccessSettingRepository settings, AppAccessTokenRepository tokens,
            AppAccessGrantRepository grants, ApiKeyRepository keys, UserGroupRepository groups,
            ActivityService activity, AuditLog audit) {
        this.settings = settings;
        this.tokens = tokens;
        this.grants = grants;
        this.keys = keys;
        this.groups = groups;
        this.activity = activity;
        this.audit = audit;
    }

    // ---------- the switch ----------

    /** Missing row reads as off: the safe answer if the seed was ever lost. */
    @Transactional(readOnly = true)
    public ApiAccessSetting getSetting() {
        return settings.findById(ApiAccessSetting.SINGLETON_ID).orElseGet(ApiAccessSetting::off);
    }

    public boolean isEnabled() {
        return getSetting().isEnabled();
    }

    @Transactional
    public ApiAccessSetting setEnabled(boolean enabled, UserAccount actor) {
        if (!actor.isSuperAdmin()) {
            audit.refused("api_access_changed", actor.getEmail(), "application access", "not a super admin");
            throw new AccessDeniedException("Only a super admin can switch application access on or off");
        }
        ApiAccessSetting setting = settings.findById(ApiAccessSetting.SINGLETON_ID)
                .orElseGet(ApiAccessSetting::off);
        if (setting.isEnabled() == enabled) {
            return setting;
        }
        setting.change(enabled, actor.getEmail(), Instant.now());
        setting = settings.save(setting);
        activity.record(ActivityAction.API_ACCESS_CHANGED, actor, null, "Application access",
                enabled ? "Switched on" : "Switched off");
        log.info("{} switched application access {}", actor.getEmail(), enabled ? "on" : "off");
        return setting;
    }

    // ---------- managing tokens ----------

    /** Group admins for their own group, super admins for any. Never a member. */
    public static boolean canManage(UserAccount account, Long groupId) {
        if (account == null || groupId == null || !account.isEnabled()) {
            return false;
        }
        if (account.isSuperAdmin()) {
            return true;
        }
        return account.getRole() == UserRole.GROUP_ADMIN && groupId.equals(account.getGroupId());
    }

    /** Anyone in the token's group may see that it exists; super admins see all. */
    public static boolean canView(UserAccount account, AppAccessToken token) {
        return account.isSuperAdmin()
                || (account.getGroupId() != null && account.getGroupId().equals(token.getGroupId()));
    }

    /** The tokens {@code viewer} may see, read-only for members. Never includes a token or a value. */
    @Transactional(readOnly = true)
    public List<AppAccessToken> findVisibleTo(UserAccount viewer) {
        if (viewer.isSuperAdmin()) {
            return tokens.findAllByOrderByNameAsc();
        }
        if (viewer.getGroupId() == null) {
            return List.of();
        }
        return tokens.findByGroup_IdOrderByNameAsc(viewer.getGroupId());
    }

    /**
     * Creates a token that can read {@code keyIds}, all of which must belong to
     * {@code groupId}. The plaintext is in the result and nowhere else.
     *
     * @throws IllegalStateException    if application access is switched off
     * @throws AccessDeniedException    if {@code actor} may not manage that group's tokens
     * @throws IllegalArgumentException if the name, validity or keys are not acceptable
     */
    @Transactional
    public IssuedAppToken create(String name, Long groupId, Collection<Long> keyIds, int validDays,
            UserAccount actor) {
        if (!isEnabled()) {
            throw new IllegalStateException("Application access is switched off; a super admin can turn it on");
        }
        if (!canManage(actor, groupId)) {
            audit.refused("app_token_created", actor.getEmail(), "group " + groupId, "not an admin of that group");
            throw new AccessDeniedException("Only a group admin or super admin can create app tokens for this group");
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Give the token a name of up to " + MAX_NAME_LENGTH + " characters");
        }
        if (validDays < 1 || validDays > MAX_VALIDITY_DAYS) {
            throw new IllegalArgumentException("A token can be valid for 1 to " + MAX_VALIDITY_DAYS + " days");
        }
        Set<Long> distinct = keyIds == null ? Set.of() : new LinkedHashSet<>(keyIds);
        if (distinct.isEmpty() || distinct.size() > MAX_KEYS_PER_TOKEN) {
            throw new IllegalArgumentException("Choose between 1 and " + MAX_KEYS_PER_TOKEN + " keys");
        }
        UserGroup group = groups.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("No such group"));
        for (Long keyId : distinct) {
            ApiKey key = keys.findById(keyId).orElseThrow(() -> new IllegalArgumentException("No such key: " + keyId));
            if (key.getGroup() == null || !groupId.equals(key.getGroup().getId())) {
                throw new IllegalArgumentException("\"" + key.getName() + "\" does not belong to " + group.getName());
            }
            if (key.isRevoked()) {
                throw new IllegalArgumentException("\"" + key.getName() + "\" has been deleted at its provider");
            }
        }

        String plaintext = AppAccessTokens.newToken();
        Instant now = Instant.now();
        AppAccessToken token = new AppAccessToken(group, trimmed, AppAccessTokens.hash(plaintext),
                AppAccessTokens.shownPart(plaintext), actor, now, now.plus(validDays, ChronoUnit.DAYS));
        distinct.forEach(token::grant);
        token = tokens.save(token);
        activity.record(ActivityAction.APP_TOKEN_CREATED, actor, token.getId(), token.getName(),
                group.getName() + "; " + distinct.size() + " key(s); valid " + validDays + " days; "
                        + token.getTokenPrefix() + "…");
        log.info("{} created {} for group {}", actor.getEmail(), token, group.getName());
        return new IssuedAppToken(token, plaintext);
    }

    /**
     * Deletes a token; any app using it is refused from its next request.
     * Allowed with the feature switched off.
     */
    @Transactional
    public AppAccessToken delete(Long tokenId, UserAccount actor) {
        AppAccessToken token = tokens.findById(tokenId)
                .orElseThrow(() -> new IllegalArgumentException("No such app token"));
        if (!canManage(actor, token.getGroupId())) {
            audit.refused("app_token_deleted", actor.getEmail(), token.getName(), "not an admin of that group");
            throw new AccessDeniedException("Only a group admin or super admin can delete this app token");
        }
        tokens.delete(token);
        activity.record(ActivityAction.APP_TOKEN_DELETED, actor, token.getId(), token.getName(),
                token.getTokenPrefix() + "…");
        log.info("{} deleted {}", actor.getEmail(), token);
        return token;
    }

    /** Keeps grants pointing at a live key when one is deleted: onto its replacement, or gone. */
    @EventListener
    public void onKeyDeleted(ApiKeyDeletedEvent event) {
        if (event.successorId() != null) {
            grants.moveToKey(event.keyId(), event.successorId());
        } else {
            grants.deleteByKeyId(event.keyId());
        }
    }

    /** The keys a new token for {@code groupId} could cover. Values are not part of it. */
    @Transactional(readOnly = true)
    public List<ApiKey> selectableKeys(Long groupId) {
        return groupId == null ? List.of() : keys.findCurrentInGroup(groupId);
    }

    /**
     * The tokens {@code viewer} may see, ready for the page: each key a token
     * covers is shown as it is now, after any rotations.
     */
    @Transactional(readOnly = true)
    public List<AppTokenView> describeVisibleTo(UserAccount viewer) {
        List<AppTokenView> views = new ArrayList<>();
        Instant now = Instant.now();
        for (AppAccessToken token : findVisibleTo(viewer)) {
            List<AppTokenView.Grant> grantViews = new ArrayList<>();
            for (AppAccessGrant grant : token.getGrants()) {
                ApiKey key = resolve(grant.getKeyId());
                boolean readable = key != null && inScope(token, key) && !key.isRevoked();
                grantViews.add(new AppTokenView.Grant(grant.getId(),
                        key == null ? "Deleted key" : key.getServiceName() + " / " + key.getName(),
                        readable, grant.getLastReadAt()));
            }
            views.add(new AppTokenView(token.getId(), token.getName(), token.getGroup().getName(),
                    token.getTokenPrefix(), token.getCreatedByName(), token.getCreatedAt(), token.getExpiresAt(),
                    token.isExpired(now), token.getLastUsedAt(), grantViews,
                    canManage(viewer, token.getGroupId())));
        }
        return views;
    }

    // ---------- the read API ----------

    /** What a token may read, without values. */
    @Transactional
    public List<AppKeyRead> listKeys(String presented, String client) {
        AppAccessToken token = authenticate(presented, client);
        List<AppKeyRead> result = new ArrayList<>();
        for (AppAccessGrant grant : token.getGrants()) {
            ApiKey key = resolve(grant.getKeyId());
            if (key != null && inScope(token, key) && !key.isRevoked()) {
                result.add(new AppKeyRead(grant.getId(), key.getId(), key.getName(), key.getServiceName(),
                        key.getExpiresOn(), null));
            }
        }
        token.markUsed(Instant.now());
        audit.allowed("app_keys_listed", actorOf(token), token.getName(), "from " + client);
        return result;
    }

    /**
     * The current value behind one reference. Follows the key's rotations
     * forward, so a replaced key is read as its replacement.
     */
    @Transactional
    public AppKeyRead readKey(String presented, Long ref, String client) {
        AppAccessToken token = authenticate(presented, client);
        AppAccessGrant grant = token.getGrants().stream()
                .filter(g -> g.getId().equals(ref))
                .findFirst()
                .orElseThrow(() -> refuse(token, "unknown reference " + ref, client,
                        AppAccessException.unknownReference()));
        ApiKey key = resolve(grant.getKeyId());
        if (key == null) {
            throw refuse(token, "reference " + ref + ": key no longer exists", client,
                    new AppAccessException(404, "key_gone", "The key behind this reference no longer exists"));
        }
        if (!inScope(token, key)) {
            // the key moved to another group after the token was made
            throw refuse(token, "reference " + ref + ": key is outside the token's group", client,
                    new AppAccessException(403, "out_of_scope", "This token may no longer read that key"));
        }
        if (key.isRevoked()) {
            throw refuse(token, "reference " + ref + ": key deleted at its provider", client,
                    new AppAccessException(410, "key_revoked", "The key has been deleted at its provider"));
        }

        Instant now = Instant.now();
        if (!key.getId().equals(grant.getKeyId())) {
            grant.followTo(key.getId());
        }
        // the history gets one entry per value an app picks up, not one per poll;
        // the audit log below has every read
        if (!key.getId().equals(grant.getLastReadKeyId())) {
            activity.recordKeyReadByApp(token.getName(), token.getTokenPrefix(), token.getGroupId(),
                    token.getGroup().getName(), key.getId(), key.getServiceName(), key.getName(),
                    grant.getLastReadKeyId() == null ? "First read, reference " + ref
                            : "Picked up the replacement for key " + grant.getLastReadKeyId() + ", reference " + ref);
        }
        grant.markRead(key.getId(), now);
        token.markUsed(now);
        keys.markRevealed(key.getId(), now);
        audit.allowed("app_key_read", actorOf(token), key.getServiceName() + " / " + key.getName(),
                "reference " + ref + " from " + client);
        return new AppKeyRead(grant.getId(), key.getId(), key.getName(), key.getServiceName(), key.getExpiresOn(),
                key.getKeyValue());
    }

    private AppAccessToken authenticate(String presented, String client) {
        if (!isEnabled()) {
            audit.refused("app_token_used", "app-token", "read API", "application access is off; from " + client);
            throw AppAccessException.disabled();
        }
        if (!AppAccessTokens.looksLikeToken(presented)) {
            audit.refused("app_token_used", "app-token", "read API", "missing or malformed token; from " + client);
            throw AppAccessException.invalidToken();
        }
        AppAccessToken token = tokens.findByTokenHash(AppAccessTokens.hash(presented)).orElse(null);
        if (token == null) {
            audit.refused("app_token_used", "app-token", "read API", "unknown token; from " + client);
            throw AppAccessException.invalidToken();
        }
        if (token.isExpired(Instant.now())) {
            audit.refused("app_token_used", actorOf(token), token.getName(), "expired; from " + client);
            throw AppAccessException.invalidToken();
        }
        return token;
    }

    /** The newest key in the rotation chain starting at {@code keyId}, or null if the key is gone. */
    private ApiKey resolve(Long keyId) {
        ApiKey key = keys.findById(keyId).orElse(null);
        for (int i = 0; key != null && i < MAX_CHAIN_LENGTH; i++) {
            ApiKey successor = keys.findByRotatedFromKeyId(key.getId()).orElse(null);
            if (successor == null) {
                return key;
            }
            key = successor;
        }
        return key;
    }

    private static boolean inScope(AppAccessToken token, ApiKey key) {
        return key.getGroup() != null && key.getGroup().getId().equals(token.getGroupId());
    }

    private AppAccessException refuse(AppAccessToken token, String reason, String client, AppAccessException e) {
        audit.refused("app_key_read", actorOf(token), token.getName(), reason + "; from " + client);
        return e;
    }

    private static String actorOf(AppAccessToken token) {
        return "app-token:" + token.getTokenPrefix();
    }
}
