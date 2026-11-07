package io.github.apikeyportal.demo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.apikeyportal.activity.ActivityAction;
import io.github.apikeyportal.activity.ActivityEvent;
import io.github.apikeyportal.activity.ActivityEventRepository;
import io.github.apikeyportal.activity.KeySource;
import io.github.apikeyportal.alerts.AlertChannel;
import io.github.apikeyportal.alerts.AlertChannelRepository;
import io.github.apikeyportal.alerts.ChannelProvider;
import io.github.apikeyportal.appaccess.AppAccessService;
import io.github.apikeyportal.integration.ServiceIntegration;
import io.github.apikeyportal.integration.ServiceIntegrationRepository;
import io.github.apikeyportal.integration.ServiceProvider;
import io.github.apikeyportal.invite.UserInvitation;
import io.github.apikeyportal.invite.UserInvitationRepository;
import io.github.apikeyportal.key.ApiKey;
import io.github.apikeyportal.key.ApiKeyRepository;
import io.github.apikeyportal.user.UserAccount;
import io.github.apikeyportal.user.UserAccountRepository;
import io.github.apikeyportal.user.UserGroup;
import io.github.apikeyportal.user.UserGroupRepository;
import io.github.apikeyportal.user.UserRole;

/**
 * Fills an empty portal with realistic-looking demo data, for screenshots and
 * trying the portal out. Runs only with the {@code demo} profile, and only once:
 * it does nothing if the demo accounts already exist.
 * <p>
 * Every value is fake - no key here works anywhere - and every demo account
 * shares one published password, so <strong>never</strong> run this against a
 * real installation.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

    public static final String DEMO_PASSWORD = "demo-password-2026";

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final UserAccountRepository accounts;
    private final UserGroupRepository groups;
    private final ApiKeyRepository keys;
    private final ServiceIntegrationRepository integrations;
    private final AlertChannelRepository channels;
    private final UserInvitationRepository invitations;
    private final ActivityEventRepository activity;
    private final AppAccessService appAccess;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final Random random = new Random(42);

    public DemoDataSeeder(UserAccountRepository accounts, UserGroupRepository groups, ApiKeyRepository keys,
            ServiceIntegrationRepository integrations, AlertChannelRepository channels,
            UserInvitationRepository invitations, ActivityEventRepository activity, AppAccessService appAccess,
            PasswordEncoder passwordEncoder, TransactionTemplate tx) {
        this.accounts = accounts;
        this.groups = groups;
        this.keys = keys;
        this.integrations = integrations;
        this.channels = channels;
        this.invitations = invitations;
        this.activity = activity;
        this.appAccess = appAccess;
        this.passwordEncoder = passwordEncoder;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accounts.findByEmailIgnoreCase("alex.carter@example.com").isPresent()) {
            log.info("Demo data already present; nothing to do");
            return;
        }
        tx.executeWithoutResult(status -> seed());
        log.warn("""

                ================================================================
                 Demo data loaded. Every account's password: {}
                 Super admin:  admin@localhost
                 Group admin:  alex.carter@example.com   (Platform Engineering)
                 Member:       sam.diaz@example.com      (Platform Engineering)
                 All values are fake. Never use this database for real keys.
                ================================================================""", DEMO_PASSWORD);
    }

    private void seed() {
        String hash = passwordEncoder.encode(DEMO_PASSWORD);
        LocalDate today = LocalDate.now();

        UserAccount admin = accounts.findAllByOrderByDisplayNameAsc().stream()
                .filter(UserAccount::isSuperAdmin).findFirst().orElseThrow();
        admin.setDisplayName("Morgan Lee");
        admin.setJobTitle("Head of Infrastructure");
        admin.setPasswordHash(hash);
        admin.setMustChangePassword(false);
        accounts.save(admin);

        UserGroup platform = groups.save(new UserGroup("Platform Engineering"));
        UserGroup data = groups.save(new UserGroup("Data & Analytics"));
        UserGroup web = groups.save(new UserGroup("Web & Growth"));

        UserAccount alex = person("alex.carter@example.com", "Alex Carter", "Platform Lead", UserRole.GROUP_ADMIN, platform, hash);
        UserAccount sam = person("sam.diaz@example.com", "Sam Diaz", "Site Reliability Engineer", UserRole.MEMBER, platform, hash);
        UserAccount priya = person("priya.nair@example.com", "Priya Nair", "Backend Engineer", UserRole.MEMBER, platform, hash);
        UserAccount tom = person("tom.becker@example.com", "Tom Becker", "DevOps Engineer", UserRole.MEMBER, platform, hash);
        UserAccount jo = person("jo.okafor@example.com", "Jo Okafor", "Data Platform Lead", UserRole.GROUP_ADMIN, data, hash);
        UserAccount mei = person("mei.chen@example.com", "Mei Chen", "Data Engineer", UserRole.MEMBER, data, hash);
        UserAccount luis = person("luis.ortega@example.com", "Luis Ortega", "ML Engineer", UserRole.MEMBER, data, hash);
        UserAccount hana = person("hana.kim@example.com", "Hana Kim", "Growth Engineering Manager", UserRole.GROUP_ADMIN, web, hash);
        UserAccount ben = person("ben.walsh@example.com", "Ben Walsh", "Frontend Engineer", UserRole.MEMBER, web, hash);
        UserAccount ava = person("ava.rossi@example.com", "Ava Rossi", "Marketing Technologist", UserRole.MEMBER, web, hash);
        admin.setGroup(platform);
        accounts.save(admin);

        // integrations: stored as connected, never verified against a real provider
        ServiceIntegration azure = integration(ServiceProvider.AZURE_AD, "Azure AD (Contoso)", "contoso.onmicrosoft.com", "6f1c2e7a-portal", platform);
        ServiceIntegration aws = integration(ServiceProvider.AWS_IAM, "AWS production", "123456789012", "AKIAIOSFODNN7EXAMPLE", platform);
        ServiceIntegration gitlab = integration(ServiceProvider.GITLAB, "GitLab CI", "https://gitlab.example.com", "platform/deploy", platform);
        ServiceIntegration cloudflare = integration(ServiceProvider.CLOUDFLARE, "Cloudflare DNS", "tmpl-dns-edit", "tmpl-dns-edit", platform);
        ServiceIntegration gcp = integration(ServiceProvider.GOOGLE_CLOUD, "Google Cloud (analytics)", "acme-analytics", "portal@acme-analytics.iam.gserviceaccount.com", data);
        ServiceIntegration twilio = integration(ServiceProvider.TWILIO, "Twilio SMS", "AC" + "3".repeat(32), "SK" + "7".repeat(32), web);
        ServiceIntegration sendgrid = integration(ServiceProvider.SENDGRID, "SendGrid mail", "mail-send", "mail-send", web);
        sendgrid.setEnabled(false);
        integrations.save(sendgrid);

        List<ApiKey> created = new ArrayList<>();
        // platform
        created.add(key("Billing service", "Stripe", "sk_live_", alex, platform, today, -200, 12, null, null));
        created.add(key("Webhook signing secret", "Stripe", "whsec_", sam, platform, today, -90, 275, null, null));
        created.add(key("Reporting service", azure.getName(), "azs~", alex, platform, today, -170, 6, azure, "app-reporting"));
        created.add(key("Deploy pipeline", azure.getName(), "azs~", tom, platform, today, -60, 120, azure, "app-deploy"));
        created.add(key("Backup uploader", aws.getName(), "AWS_ACCESS_KEY_ID=AKIA", sam, platform, today, -88, 2, aws, "backup-uploader-3f2a"));
        created.add(key("Log shipper", aws.getName(), "AWS_ACCESS_KEY_ID=AKIA", priya, platform, today, -30, 150, aws, "log-shipper-91bc"));
        created.add(key("Release bot", gitlab.getName(), "glpat-", tom, platform, today, -350, -4, gitlab, "4411"));
        created.add(key("Container registry", gitlab.getName(), "glpat-", priya, platform, today, -40, 325, gitlab, "4502"));
        created.add(key("DNS automation", cloudflare.getName(), "cf_", alex, platform, today, -20, 70, cloudflare, "tok-dns"));
        created.add(key("Monitoring", "Datadog", "dd_api_", sam, platform, today, -400, 25, null, null));
        created.add(key("On-call paging", "PagerDuty", "u+", tom, platform, today, -500, null, null, null));
        created.add(key("Infra repo access", "GitHub", "github_pat_", priya, platform, today, -80, 10, null, null));
        created.add(key("Status page", "Atlassian", "ATATT3x", alex, platform, today, -120, -12, null, null));
        created.add(key("Incident bot", "Slack", "xoxb-", sam, platform, today, -45, 320, null, null));
        created.add(key("Error tracking", "Sentry", "sntrys_", priya, platform, today, -210, 155, null, null));
        created.add(key("Terraform state", aws.getName(), "AWS_ACCESS_KEY_ID=AKIA", alex, platform, today, -150, 30, aws, "terraform-77de"));
        // data
        created.add(key("Warehouse loader", gcp.getName(), "{\"type\":\"service_account\",\"private_key_id\":\"", jo, data, today, -175, 5, gcp, "loader@acme-analytics.iam.gserviceaccount.com"));
        created.add(key("Dashboard exporter", gcp.getName(), "{\"type\":\"service_account\",\"private_key_id\":\"", mei, data, today, -60, 210, gcp, "exporter@acme-analytics.iam.gserviceaccount.com"));
        created.add(key("Research assistant", "OpenAI", "sk-proj-", luis, data, today, -30, 60, null, null));
        created.add(key("Summariser", "Anthropic", "sk-ant-api03-", luis, data, today, -15, 165, null, null));
        created.add(key("Snowflake loader", "Snowflake", "snf_", mei, data, today, -300, -30, null, null));
        created.add(key("dbt Cloud", "dbt", "dbtc_", jo, data, today, -100, 18, null, null));
        created.add(key("Notebook access", "GitHub", "github_pat_", mei, data, today, -70, 290, null, null));
        created.add(key("Feature store", "Databricks", "dapi", luis, data, today, -20, 345, null, null));
        // web
        created.add(key("Sign-up verification", twilio.getName(), "TWILIO_ACCOUNT_SID=AC", hana, web, today, -85, 4, twilio, "AC" + "3".repeat(32)));
        created.add(key("Order notifications", twilio.getName(), "TWILIO_ACCOUNT_SID=AC", ben, web, today, -20, 160, twilio, "AC" + "3".repeat(32)));
        created.add(key("Newsletter", sendgrid.getName(), "SG.", ava, web, today, -95, -2, sendgrid, "account"));
        created.add(key("Checkout", "Stripe", "rk_live_", ben, web, today, -250, 110, null, null));
        created.add(key("Site analytics", "Google Analytics", "G-", ava, web, today, -365, null, null, null));
        created.add(key("Maps", "Mapbox", "pk.", ben, web, today, -40, 35, null, null));
        created.add(key("Search", "Algolia", "alg_", hana, web, today, -160, 200, null, null));
        created.add(key("CMS preview", "Contentful", "CFPAT-", ava, web, today, -50, 22, null, null));
        created.add(key("Chat widget", "Intercom", "dG9r", ben, web, today, -130, 8, null, null));
        created.add(key("Ad conversions", "Meta", "EAAG", ava, web, today, -25, 55, null, null));

        // a rotated pair: the old key replaced a week ago, waiting for its date
        ApiKey rotatedFrom = created.get(4);
        ApiKey replacement = new ApiKey(rotatedFrom.getName(), rotatedFrom.getServiceName(),
                "AWS_ACCESS_KEY_ID=AKIA" + hex(16) + "\nAWS_SECRET_ACCESS_KEY=" + hex(40), today.minusDays(5),
                today.plusDays(83));
        replacement.assignTo(rotatedFrom.getOwner(), rotatedFrom.getGroup());
        replacement.setNotes("Replaces \"Backup uploader\", which expires on " + rotatedFrom.getExpiresOn()
                + ". Minted automatically through AWS production.");
        replacement.recordProvenance(aws.getId(), "backup-uploader-3f2a", true, rotatedFrom.getId());
        replacement = keys.save(replacement);

        // reveals: most keys have been looked at recently, a few never have
        Map<Long, Integer> neverRevealed = new HashMap<>();
        for (int i : new int[] {9, 10, 21, 28, 30}) {
            neverRevealed.put(created.get(i).getId(), i);
        }
        for (ApiKey key : created) {
            if (!neverRevealed.containsKey(key.getId())) {
                keys.markRevealed(key.getId(), Instant.now().minus(random.nextInt(25) + 1, ChronoUnit.DAYS));
            }
        }
        keys.markRotationFailed(created.get(2).getId(), Instant.now().minus(1, ChronoUnit.HOURS),
                "Could not add the client secret (HTTP 403): Insufficient privileges to complete the operation.");
        keys.markRotationFailed(created.get(24).getId(), Instant.now().minus(3, ChronoUnit.HOURS),
                "Could not create the API key (HTTP 401): Authenticate");

        // chat alerts
        AlertChannel teams = new AlertChannel(platform, ChannelProvider.TEAMS, "#platform-alerts",
                "https://example.webhook.office.com/webhookb2/demo");
        teams.markSent(Instant.now().minus(2, ChronoUnit.HOURS));
        channels.save(teams);
        AlertChannel slack = new AlertChannel(web, ChannelProvider.SLACK, "#growth-ops",
                "https://hooks.slack.com/services/T000/B000/demo");
        slack.markSent(Instant.now().minus(1, ChronoUnit.DAYS));
        channels.save(slack);

        // pending invitations
        invitations.save(new UserInvitation("riley.james@example.com", hex(64), UserRole.MEMBER, platform, alex,
                Instant.now().plus(20, ChronoUnit.HOURS)));
        invitations.save(new UserInvitation("noah.silva@example.com", hex(64), UserRole.MEMBER, data, jo,
                Instant.now().plus(6, ChronoUnit.HOURS)));

        // history over the last few weeks
        UserAccount[] actors = {alex, sam, priya, tom, jo, mei, luis, hana, ben, ava, admin};
        for (int i = 0; i < 70; i++) {
            ApiKey key = created.get(random.nextInt(created.size()));
            UserAccount actor = key.getOwner();
            Instant at = Instant.now().minus(random.nextInt(40 * 24 * 60) + 30, ChronoUnit.MINUTES);
            int kind = random.nextInt(10);
            ActivityAction action = kind < 5 ? ActivityAction.KEY_REVEALED
                    : kind < 7 ? ActivityAction.KEY_UPDATED : kind < 9 ? ActivityAction.KEY_CREATED
                    : ActivityAction.KEY_DELETED;
            String detail = switch (action) {
                case KEY_REVEALED -> random.nextBoolean() ? "Shown on screen" : "Copied to the clipboard";
                case KEY_UPDATED -> random.nextBoolean() ? "Notes" : "Expiry date";
                case KEY_CREATED -> key.getIntegrationId() != null ? key.getServiceName() : null;
                default -> null;
            };
            KeySource source = action == ActivityAction.KEY_CREATED
                    ? (key.getIntegrationId() != null ? KeySource.PROVISIONED : KeySource.MANUAL) : null;
            activity.save(new ActivityEvent(action, actor, key.getId(),
                    key.getServiceName() + " / " + key.getName(), source, detail, at));
        }
        activity.save(new ActivityEvent(ActivityAction.INTEGRATION_CONNECTED, alex, aws.getId(), aws.getName(),
                null, "Amazon Web Services (IAM)", Instant.now().minus(38, ChronoUnit.DAYS)));
        activity.save(new ActivityEvent(ActivityAction.INVITATION_SENT, alex, null, "riley.james@example.com",
                null, "Member", Instant.now().minus(4, ChronoUnit.HOURS)));
        activity.save(new ActivityEvent(ActivityAction.INTEGRATION_DISABLED, hana, sendgrid.getId(),
                sendgrid.getName(), null, null, Instant.now().minus(2, ChronoUnit.DAYS)));

        // app access: on, with a couple of tokens
        appAccess.setEnabled(true, admin);
        appAccess.create("billing-service (production)", platform.getId(),
                List.of(created.get(0).getId(), created.get(1).getId()), 90, alex);
        appAccess.create("backup job", platform.getId(), List.of(rotatedFrom.getId()), 180, alex);
        appAccess.create("nightly ETL", data.getId(),
                List.of(created.get(16).getId(), created.get(20).getId()), 30, jo);
        appAccess.create("notification worker", web.getId(), List.of(created.get(25).getId()), 365, hana);
        log.info("Seeded {} keys, {} people and 3 groups", created.size() + 1, actors.length);
    }

    private UserAccount person(String email, String name, String title, UserRole role, UserGroup group, String hash) {
        UserAccount account = new UserAccount(email, name, hash, role, group);
        account.setJobTitle(title);
        return accounts.save(account);
    }

    private ServiceIntegration integration(ServiceProvider provider, String name, String tenant, String client,
            UserGroup group) {
        ServiceIntegration integration = new ServiceIntegration(provider, name, tenant, client, "demo-" + hex(24));
        integration.assignTo(group);
        integration.markVerified(Instant.now().minus(random.nextInt(72) + 1, ChronoUnit.HOURS));
        return integrations.save(integration);
    }

    /**
     * @param createdDaysAgo negative: days before today
     * @param expiresInDays  null for no expiry; negative for already expired
     */
    private ApiKey key(String name, String service, String valuePrefix, UserAccount owner, UserGroup group,
            LocalDate today, int createdDaysAgo, Integer expiresInDays, ServiceIntegration via, String resource) {
        ApiKey key = new ApiKey(name, service, valuePrefix + "demo" + hex(32), today.plusDays(createdDaysAgo),
                expiresInDays == null ? null : today.plusDays(expiresInDays));
        key.assignTo(owner, group);
        if (via != null) {
            key.setNotes("Created via the " + via.getName() + " integration.");
            key.recordProvenance(via.getId(), resource, null, null);
        } else if (random.nextInt(3) == 0) {
            key.setNotes("Owned by " + owner.getDisplayName() + "'s team. Rotate with the vendor dashboard.");
        }
        return keys.save(key);
    }

    private String hex(int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            text.append(Character.forDigit(random.nextInt(16), 16));
        }
        return text.toString();
    }
}
