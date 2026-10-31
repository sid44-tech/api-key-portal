package com.sidbhat.apikeyportal.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.user.TestUsers;

@ExtendWith(MockitoExtension.class)
class ExpiryReminderMailerTest {

    @Mock
    private ObjectProvider<JavaMailSender> senderProvider;

    @Mock
    private JavaMailSender sender;

    private ExpiryReminderMailer mailer;
    private ApiKey key;

    @BeforeEach
    void setUp() {
        mailer = new ExpiryReminderMailer(senderProvider,
                new NotificationProperties(true, "0 0 8 * * *", "no-reply@example.com", "https://keys.example.com"));
        key = new ApiKey("Deploy token", "GitHub", "ghp_SECRET_VALUE_0123", LocalDate.of(2026, 1, 15),
                LocalDate.of(2026, 10, 20));
        key.setNotes("Used by the release pipeline.\nRotate with the platform team.");
        key.assignTo(TestUsers.alex(), TestUsers.platformTeam());
        ReflectionTestUtils.setField(key, "id", 42L);
    }

    @Test
    void theEmailCarriesEveryPieceOfMetadataButNeverTheValue() {
        String body = mailer.body(key, ReminderThreshold.ONE_WEEK, 6);

        assertThat(body)
                .contains("Alex Carter")
                .contains("GitHub")
                .contains("Deploy token")
                .contains("Tuesday 20 October 2026")
                .contains("Thursday 15 January 2026")
                .contains("Platform team")
                .contains("Used by the release pipeline.")
                .contains("https://keys.example.com/keys/42")
                .contains("Mute expiration notifications")
                .contains("a week reminder");
        assertThat(body).doesNotContain("ghp_SECRET_VALUE_0123");
    }

    @Test
    void theSubjectSaysHowSoon() {
        assertThat(ExpiryReminderMailer.subject(key, 6)).isEqualTo("Your GitHub key \"Deploy token\" expires in 6 days");
        assertThat(ExpiryReminderMailer.subject(key, 1)).endsWith("expires tomorrow");
        assertThat(ExpiryReminderMailer.subject(key, 0)).endsWith("expires today");
    }

    @Test
    void theLastReminderDoesNotPromiseAnother() {
        assertThat(mailer.body(key, ReminderThreshold.ONE_DAY, 1)).doesNotContain("You'll get another");
        assertThat(mailer.body(key, ReminderThreshold.ONE_MONTH, 25)).contains("You'll get another");
    }

    @Test
    void sendsToTheOwnerFromTheConfiguredAddress() {
        when(senderProvider.getIfAvailable()).thenReturn(sender);

        assertThat(mailer.send(key, ReminderThreshold.ONE_WEEK, 6)).isTrue();

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().getTo()).containsExactly("alex@example.com");
        assertThat(message.getValue().getFrom()).isEqualTo("no-reply@example.com");
        assertThat(message.getValue().getText()).doesNotContain("ghp_SECRET_VALUE_0123");
    }

    @Test
    void reportsFalseWithNoMailServerSoNothingIsRecorded() {
        when(senderProvider.getIfAvailable()).thenReturn(null);

        assertThat(mailer.isAvailable()).isFalse();
        assertThat(mailer.send(key, ReminderThreshold.ONE_WEEK, 6)).isFalse();
    }

    @Test
    void reportsFalseWhenTheServerRejectsTheMessage() {
        when(senderProvider.getIfAvailable()).thenReturn(sender);
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("down"))
                .when(sender).send(any(SimpleMailMessage.class));

        assertThat(mailer.send(key, ReminderThreshold.ONE_WEEK, 6)).isFalse();
    }
}
