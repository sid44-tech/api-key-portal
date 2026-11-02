package com.sidbhat.apikeyportal.integration;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.sidbhat.apikeyportal.activity.ActivityAction;
import com.sidbhat.apikeyportal.activity.ActivityService;
import com.sidbhat.apikeyportal.logging.AuditLog;
import com.sidbhat.apikeyportal.integration.aws.AwsIamClient;
import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient;
import com.sidbhat.apikeyportal.integration.cloudflare.CloudflareClient;
import com.sidbhat.apikeyportal.integration.gitlab.GitLabClient;
import com.sidbhat.apikeyportal.integration.google.GoogleIamClient;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserRole;

/**
 * Integrations belong to a group. Everyone in the group can use one to provision
 * a key, but only a group admin can connect, re-test or remove one - the stored
 * credentials can create new credentials in the provider.
 */
@Service
public class ServiceIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(ServiceIntegrationService.class);

    private final ServiceIntegrationRepository repository;
    private final AzureGraphClient graphClient;
    private final GoogleIamClient googleIamClient;
    private final AwsIamClient awsIamClient;
    private final GitLabClient gitLabClient;
    private final CloudflareClient cloudflareClient;
    private final ActivityService activity;
    private final AuditLog audit;

    public ServiceIntegrationService(ServiceIntegrationRepository repository, AzureGraphClient graphClient,
            GoogleIamClient googleIamClient, AwsIamClient awsIamClient, GitLabClient gitLabClient,
            CloudflareClient cloudflareClient, ActivityService activity, AuditLog audit) {
        this.repository = repository;
        this.graphClient = graphClient;
        this.googleIamClient = googleIamClient;
        this.awsIamClient = awsIamClient;
        this.gitLabClient = gitLabClient;
        this.cloudflareClient = cloudflareClient;
        this.activity = activity;
        this.audit = audit;
    }

    /** True if this person may connect, re-test or remove integrations for their group. */
    public static boolean canManage(UserAccount account) {
        return account.getRole() != UserRole.MEMBER && account.getGroupId() != null;
    }

    /** The integrations {@code viewer}'s group has set up; everything, for a super admin. */
    public List<ServiceIntegration> findAll(UserAccount viewer) {
        if (viewer.isSuperAdmin()) {
            return repository.findAllByOrderByNameAsc();
        }
        return viewer.getGroupId() == null ? List.of()
                : repository.findByGroup_IdOrderByNameAsc(viewer.getGroupId());
    }

    /**
     * @throws AccessDeniedException if the integration belongs to another group
     */
    public ServiceIntegration getById(Long id, UserAccount viewer) {
        ServiceIntegration integration = getById(id);
        if (!canSee(integration, viewer)) {
            throw new AccessDeniedException("Integration " + id + " belongs to another group");
        }
        return integration;
    }

    ServiceIntegration getById(Long id) {
        return repository.findById(id).orElseThrow(() -> new ServiceIntegrationNotFoundException(id));
    }

    /**
     * Verifies the credentials against the provider and saves the integration for
     * {@code admin}'s group. Saving never happens if verification fails, so a stored
     * integration is known to have worked at least once.
     *
     * @throws ProvisioningException if the provider rejects the credentials
     * @throws AccessDeniedException if {@code admin} does not administer a group
     */
    public ServiceIntegration connect(ServiceProvider provider, String name, String tenantId, String clientId,
            String clientSecret, UserAccount admin) {
        requireGroupAdmin(admin);
        ServiceIntegration integration = new ServiceIntegration(provider, name.trim(), tenantId.trim(),
                clientId.trim(), clientSecret.trim());
        integration.assignTo(admin.getGroup());
        verifyWithProvider(integration);
        integration.markVerified(Instant.now());
        ServiceIntegration saved = repository.save(integration);
        activity.record(ActivityAction.INTEGRATION_CONNECTED, admin, saved.getId(), saved.getName(),
                provider.getLabel());
        log.info("Connected {} for {}", saved, admin.getEmail());
        return saved;
    }

    /**
     * Re-checks that the stored credentials still authenticate, and records when they last did.
     *
     * @throws ProvisioningException if the provider rejects the credentials
     */
    public ServiceIntegration test(Long id, UserAccount admin) {
        ServiceIntegration integration = requireAdministrable(id, admin);
        verifyWithProvider(integration);
        integration.markVerified(Instant.now());
        ServiceIntegration saved = repository.save(integration);
        // an admin exercising a stored provider credential; the credential
        // itself is never named, only the integration holding it
        audit.allowed("integration_verified", admin.getEmail(), saved.getName(),
                saved.getProvider().getLabel());
        return saved;
    }

    /**
     * The group-level switch. Off, nobody in the group can mint keys through this
     * integration - the provision form refuses, the add-key page stops offering
     * it, and automatic rotation skips keys minted through it. The credential
     * stays stored, so switching back on is one click, not a re-setup.
     * <p>
     * Who may flip it is the same rule as test and remove: a super admin for any
     * group, a group admin for their own.
     */
    public ServiceIntegration setEnabled(Long id, boolean enabled, UserAccount admin) {
        ServiceIntegration integration = requireAdministrable(id, admin);
        if (integration.isEnabled() == enabled) {
            return integration;
        }
        integration.setEnabled(enabled);
        ServiceIntegration saved = repository.save(integration);
        activity.record(enabled ? ActivityAction.INTEGRATION_ENABLED : ActivityAction.INTEGRATION_DISABLED,
                admin, saved.getId(), saved.getName(), saved.getProvider().getLabel());
        log.info("{} switched {} {}", admin.getEmail(), saved, enabled ? "on" : "off");
        return saved;
    }

    /**
     * The integrations {@code viewer} can actually mint keys through right now:
     * their group's, minus the switched-off ones. The add-key page offers these;
     * the integrations page itself uses {@link #findAll} so an admin can still
     * see - and re-enable - what is off.
     */
    public List<ServiceIntegration> findUsable(UserAccount viewer) {
        return findAll(viewer).stream().filter(ServiceIntegration::isEnabled).toList();
    }

    /** Called after another operation has already proven the credentials work. */
    public void markVerified(ServiceIntegration integration) {
        integration.markVerified(Instant.now());
        repository.save(integration);
    }

    public ServiceIntegration delete(Long id, UserAccount admin) {
        ServiceIntegration integration = requireAdministrable(id, admin);
        repository.delete(integration);
        activity.record(ActivityAction.INTEGRATION_REMOVED, admin, integration.getId(), integration.getName(),
                integration.getProvider().getLabel());
        log.info("Deleted {}", integration);
        return integration;
    }

    private ServiceIntegration requireAdministrable(Long id, UserAccount admin) {
        ServiceIntegration integration = getById(id, admin);
        requireGroupAdmin(admin);
        return integration;
    }

    /**
     * An integration is a group's shared property, so whoever sets one up has to be
     * an administrator <em>of a group</em> - including a super admin. Connecting one
     * without a group would store credentials nobody could then use, which is the
     * opposite of the point.
     */
    private static void requireGroupAdmin(UserAccount admin) {
        if (admin.getRole() == UserRole.MEMBER) {
            throw new AccessDeniedException("Only a group admin can manage service integrations");
        }
        if (admin.getGroupId() == null) {
            throw new AccessDeniedException("Integrations belong to a group, and you are not in one yet");
        }
    }

    private static boolean canSee(ServiceIntegration integration, UserAccount viewer) {
        return viewer.isSuperAdmin()
                || (viewer.getGroupId() != null && viewer.getGroupId().equals(integration.getGroupId()));
    }

    private void verifyWithProvider(ServiceIntegration integration) {
        // acquiring a token is the cheapest call that proves the stored credentials are right
        switch (integration.getProvider()) {
            case AZURE_AD -> graphClient.acquireToken(integration.getTenantId(), integration.getClientId(),
                    integration.getClientSecret());
            case GOOGLE_CLOUD -> googleIamClient.acquireToken(integration.getClientId(),
                    integration.getClientSecret());
            case GITLAB -> gitLabClient.verifyProjectAccess(integration.getTenantId(),
                    integration.getClientSecret(), integration.getClientId());
            case CLOUDFLARE -> {
                cloudflareClient.verifyToken(integration.getClientSecret());
                // reading the template proves it exists and has policies to copy
                cloudflareClient.templateToken(integration.getClientSecret(), integration.getTenantId());
            }
            case AWS_IAM -> {
                // STS says whose credentials these are; a key from the wrong
                // account would otherwise fail much later, on first provisioning
                String account = awsIamClient.callerAccount(integration.getClientId(),
                        integration.getClientSecret());
                if (!account.equals(integration.getTenantId())) {
                    throw new ProvisioningException("That access key belongs to AWS account " + account
                            + ", not " + integration.getTenantId() + " - double-check the account ID.");
                }
            }
        }
    }
}
