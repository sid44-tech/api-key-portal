package com.sidbhat.apikeyportal.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.sidbhat.apikeyportal.integration.ServiceIntegrationRepository;
import com.sidbhat.apikeyportal.key.ApiKeyRepository;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountRepository;
import com.sidbhat.apikeyportal.user.UserGroup;
import com.sidbhat.apikeyportal.user.UserGroupRepository;
import com.sidbhat.apikeyportal.user.UserRole;

/**
 * Seeds the super admin on an empty portal, and hands them anything that
 * predates accounts existing.
 * <p>
 * Nobody can sign themselves up, so a fresh install has to start with one account
 * or there would be no way in at all. It gets the default password from
 * {@code PORTAL_ADMIN_PASSWORD} and is marked as having to replace it: until they
 * do, that account can reach nothing but the change-password page.
 * <p>
 * A deployment that would rather not have a plaintext password in its
 * configuration can set {@code PORTAL_ADMIN_PASSWORD_HASH} instead. That is taken
 * as a password somebody chose on purpose, so no change is forced. It is also
 * what an upgrade from the old single-user portal lands on: the one set of
 * credentials in {@code PORTAL_USERNAME} / {@code PORTAL_PASSWORD_HASH} (still
 * honoured) becomes a real super admin account, and every key and integration
 * already in the database becomes theirs - otherwise they would belong to nobody
 * and show up for no one.
 * <p>
 * This runs as an {@link InitializingBean} rather than an {@code ApplicationRunner}
 * so that it finishes - or aborts the startup - before the web server starts
 * accepting requests. Flyway has already run by this point, because the migration
 * initializer is a dependency of the entity manager behind these repositories.
 */
@Component
public class BootstrapAdminInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final SecurityProperties properties;
    private final UserAccountRepository accounts;
    private final UserGroupRepository groups;
    private final ApiKeyRepository keys;
    private final ServiceIntegrationRepository integrations;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;

    public BootstrapAdminInitializer(SecurityProperties properties, UserAccountRepository accounts,
            UserGroupRepository groups, ApiKeyRepository keys, ServiceIntegrationRepository integrations,
            PasswordEncoder passwordEncoder, TransactionTemplate transactionTemplate) {
        this.properties = properties;
        this.accounts = accounts;
        this.groups = groups;
        this.keys = keys;
        this.integrations = integrations;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        // the transaction is managed by hand: @Transactional wouldn't apply to a
        // lifecycle callback the container invokes on the target instance
        transactionTemplate.executeWithoutResult(status -> bootstrap());
    }

    void bootstrap() {
        if (accounts.count() > 0) {
            claimLeftovers();
            return;
        }
        boolean hashConfigured = StringUtils.hasText(properties.getAdminPasswordHash());
        String passwordHash = hashConfigured ? properties.getAdminPasswordHash()
                : passwordEncoder.encode(properties.getAdminPassword());

        UserGroup group = groups.findByNameIgnoreCase(properties.getAdminGroupName())
                .orElseGet(() -> groups.save(new UserGroup(properties.getAdminGroupName())));
        UserAccount admin = new UserAccount(properties.getAdminEmail(), "Administrator", passwordHash,
                UserRole.SUPER_ADMIN, group);
        admin.setMustChangePassword(!hashConfigured);
        admin = accounts.save(admin);

        if (hashConfigured) {
            log.info("Seeded the super admin {} from the configured password hash", admin);
        } else {
            log.warn("Seeded the super admin {} with the default password from PORTAL_ADMIN_PASSWORD. "
                    + "Sign in at /login and change it - the portal will not let that account do "
                    + "anything else until you do.", admin);
        }
        claimLeftovers();
    }

    /**
     * Gives keys and integrations with no owner to the longest-standing super admin.
     * A no-op except on the first start after the multi-user upgrade.
     */
    private void claimLeftovers() {
        accounts.findAllByOrderByDisplayNameAsc().stream()
                .filter(UserAccount::isSuperAdmin)
                .filter(account -> account.getGroup() != null)
                .findFirst()
                .ifPresent(admin -> {
                    int claimedKeys = keys.claimOwnerless(admin, admin.getGroup());
                    int claimedIntegrations = integrations.claimUngrouped(admin.getGroup());
                    if (claimedKeys > 0 || claimedIntegrations > 0) {
                        log.info("Assigned {} pre-existing key(s) and {} integration(s) to {}",
                                claimedKeys, claimedIntegrations, admin);
                    }
                });
    }
}
