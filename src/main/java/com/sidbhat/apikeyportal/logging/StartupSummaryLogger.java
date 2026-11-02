package com.sidbhat.apikeyportal.logging;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.sidbhat.apikeyportal.notification.NotificationProperties;
import com.sidbhat.apikeyportal.security.LockoutProperties;
import com.sidbhat.apikeyportal.sso.SsoService;

/**
 * Says what shape this instance is in, once, at startup.
 * <p>
 * Reading an audit trail a month later means knowing what the portal was doing
 * at the time: which database it was writing to, whether single sign-on was on,
 * whether reminder emails could be sent, how many failed sign-ins it took to
 * lock an account. Without this, "there are no lockouts in the log" is
 * ambiguous - nobody tripped the limit, or somebody had set it to a thousand.
 * <p>
 * Settings only, never their values: the database URL and whether a credential
 * is present, not the credential.
 */
@Component
public class StartupSummaryLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSummaryLogger.class);

    private final DataSource dataSource;
    private final LockoutProperties lockout;
    private final NotificationProperties notifications;
    private final SsoService sso;
    private final AuditLog audit;

    public StartupSummaryLogger(DataSource dataSource, LockoutProperties lockout,
            NotificationProperties notifications, SsoService sso, AuditLog audit) {
        this.dataSource = dataSource;
        this.lockout = lockout;
        this.notifications = notifications;
        this.sso = sso;
        this.audit = audit;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Portal started: database={}, singleSignOn={}, expiryReminders={}, "
                        + "signInLockout=after {} failures for {}",
                describeDatabase(), sso.isEnabled() ? "on" : "off",
                notifications.isEnabled() ? "on" : "off",
                lockout.getMaxAttempts(), lockout.getLockoutFor());
        audit.system("portal_started", "database=" + describeDatabase());
    }

    /**
     * The product and URL the portal is writing to - no username, no password.
     * Worth knowing because the all-in-one container, a bare jar and a managed
     * PostgreSQL all look identical from the log otherwise.
     */
    private String describeDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName() + " at "
                    + withoutCredentials(connection.getMetaData().getURL());
        } catch (SQLException e) {
            // never a reason to fail starting up; the rest of the line is still useful
            log.warn("Could not describe the database for the startup summary", e);
            return "unknown";
        }
    }

    /**
     * Strips a {@code user:password@} that somebody put in the JDBC URL itself.
     * The portal's own configuration keeps them apart, but the URL is handed in
     * whole and this is the one line that writes it down.
     */
    static String withoutCredentials(String url) {
        return url == null ? "unknown" : url.replaceAll("//[^/@]*@", "//");
    }
}
