package io.github.apikeyportal.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The shape of an audit line, which is a contract: somebody will grep it, and
 * something may well parse it.
 */
class AuditLogTest {

    private final AuditLog audit = new AuditLog();

    private ListAppender<ILoggingEvent> appender;
    private Logger auditLogger;

    @BeforeEach
    void captureTheAuditLogger() {
        auditLogger = (Logger) LoggerFactory.getLogger("portal.audit");
        appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
    }

    @AfterEach
    void releaseIt() {
        auditLogger.detachAppender(appender);
    }

    private ILoggingEvent onlyEvent() {
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0);
    }

    private String message() {
        return onlyEvent().getFormattedMessage();
    }

    @Test
    void anAllowedActionIsRecordedAtInfo() {
        audit.allowed("key_revealed", "sam@example.com", "Stripe / Prod secret", "Copied to the clipboard");

        assertThat(onlyEvent().getLevel()).isEqualTo(Level.INFO);
        assertThat(message()).isEqualTo("event=key_revealed outcome=ok actor=sam@example.com "
                + "target=\"Stripe / Prod secret\" detail=\"Copied to the clipboard\"");
    }

    @Test
    void aRefusalIsAWarningBecauseSomebodyMayBeTryingThingsOn() {
        audit.refused("access_denied", "sam@example.com", "/groups", "Only a super admin can do that");

        assertThat(onlyEvent().getLevel()).isEqualTo(Level.WARN);
        assertThat(message()).isEqualTo("event=access_denied outcome=refused actor=sam@example.com "
                + "target=/groups reason=\"Only a super admin can do that\"");
    }

    @Test
    void aMissingFieldIsADashRatherThanTheWordNull() {
        audit.allowed("sign_out", null, "sam@example.com", null);

        assertThat(message()).isEqualTo("event=sign_out outcome=ok actor=- target=sam@example.com");
    }

    @Test
    void aValueWithASpaceIsQuotedSoOneFieldCannotLookLikeTwo() {
        assertThat(AuditLog.value("GitHub / CI token")).isEqualTo("\"GitHub / CI token\"");
        assertThat(AuditLog.value("simple")).isEqualTo("simple");
    }

    @Test
    void aNewlineCannotBeUsedToForgeASecondLogLine() {
        // the display name is somebody's own text; it must not be able to write
        // a line of its own into the audit trail
        audit.allowed("profile_updated", "sam@example.com",
                "Sam\nevent=key_revealed outcome=ok actor=admin@example.com", null);

        assertThat(appender.list).hasSize(1);
        assertThat(message()).doesNotContain("\n");
        assertThat(message()).contains("\\n");
    }

    @Test
    void aQuoteInAValueIsEscapedRatherThanClosingTheField() {
        assertThat(AuditLog.value("say \"hello\" now")).isEqualTo("\"say \\\"hello\\\" now\"");
    }

    @Test
    void aSystemActionHasNoActorButIsStillRecorded() {
        audit.system("portal_started", "database=PostgreSQL");

        assertThat(message()).isEqualTo("event=portal_started outcome=ok actor=system "
                + "detail=database=PostgreSQL");
    }

    @Test
    void theJdbcUrlIsLoggedWithoutAnyCredentialsSomebodyPutInIt() {
        assertThat(StartupSummaryLogger.withoutCredentials(
                "jdbc:postgresql://keyportal:hunter2@db.example.com:5432/keyportal"))
                .isEqualTo("jdbc:postgresql://db.example.com:5432/keyportal");
        assertThat(StartupSummaryLogger.withoutCredentials("jdbc:sqlite:keyportal.db"))
                .isEqualTo("jdbc:sqlite:keyportal.db");
    }
}
