package com.sidbhat.apikeyportal.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.notification.NotificationProperties;
import com.sidbhat.apikeyportal.notification.ReminderThreshold;
import com.sidbhat.apikeyportal.theme.ThemeService;
import com.sidbhat.apikeyportal.user.TestUsers;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertNotifierTest {

    @Mock
    private AlertChannelRepository channels;

    @Mock
    private WebhookClient client;

    @Mock
    private ThemeService theme;

    private AlertNotifier notifier;
    private AlertChannel teams;
    private AlertChannel slack;
    private ApiKey key;

    @BeforeEach
    void setUp() {
        notifier = new AlertNotifier(channels, client,
                new NotificationProperties(true, "0 0 8 * * *", "no-reply@example.com", "https://keys.example.com"),
                theme);
        when(theme.portalName()).thenReturn("Keys at Contoso");
        teams = new AlertChannel(TestUsers.platformTeam(), ChannelProvider.TEAMS, "#platform-alerts",
                "https://hooks.example.com/teams");
        slack = new AlertChannel(TestUsers.platformTeam(), ChannelProvider.SLACK, "#platform",
                "https://hooks.slack.com/services/x");
        when(channels.findByGroup_IdOrderByProvider(TestUsers.platformTeam().getId())).thenReturn(List.of(teams, slack));
        key = new ApiKey("Deploy token", "GitHub", "ghp_SECRET_VALUE", LocalDate.now().minusDays(100),
                LocalDate.now().plusDays(7));
        key.assignTo(TestUsers.alex(), TestUsers.platformTeam());
        ReflectionTestUtils.setField(key, "id", 42L);
    }

    @Test
    void anExpiryGoesToEveryChannelTheGroupHasEachInItsOwnFormatAndNeverTheValue() {
        assertThat(notifier.expiring(key, ReminderThreshold.ONE_WEEK, 7)).isTrue();

        ArgumentCaptor<AlertCard> card = ArgumentCaptor.forClass(AlertCard.class);
        verify(client).post(eq(ChannelProvider.TEAMS), eq("https://hooks.example.com/teams"), card.capture());
        verify(client).post(eq(ChannelProvider.SLACK), eq("https://hooks.slack.com/services/x"), any());
        assertThat(card.getValue().title()).isEqualTo("Key expires in 7 days: GitHub / Deploy token");
        assertThat(card.getValue().linkUrl()).isEqualTo("https://keys.example.com/keys/42");
        assertThat(card.getValue().toTeams().toString()).doesNotContain("ghp_SECRET_VALUE");
        assertThat(card.getValue().toSlack().toString()).doesNotContain("ghp_SECRET_VALUE");
        assertThat(teams.getLastSentAt()).isNotNull();
        assertThat(slack.getLastSentAt()).isNotNull();
    }

    @Test
    void eachChannelKeepsItsOwnToggles() {
        slack.setNotifyExpiries(false);

        assertThat(notifier.expiring(key, ReminderThreshold.ONE_WEEK, 7)).isTrue();

        verify(client).post(eq(ChannelProvider.TEAMS), any(), any());
        verify(client, never()).post(eq(ChannelProvider.SLACK), any(), any());
    }

    @Test
    void aSwitchedOffChannelGetsNothing() {
        teams.setEnabled(false);
        slack.setEnabled(false);

        assertThat(notifier.expiring(key, ReminderThreshold.ONE_WEEK, 7)).isFalse();
        notifier.rotationFailed(key, "Azure said no");

        verify(client, never()).post(any(), any(), any());
    }

    @Test
    void oneChannelFailingDoesNotStopTheOtherAndIsKeptOnTheChannel() {
        doThrow(new AlertException("Slack rejected the message (HTTP 404): no_service"))
                .when(client).post(eq(ChannelProvider.SLACK), any(), any());

        // Teams took it, so the reminder still counts as announced
        assertThat(notifier.expiring(key, ReminderThreshold.ONE_WEEK, 7)).isTrue();

        assertThat(slack.getLastError()).contains("no_service");
        assertThat(slack.getLastSentAt()).isNull();
        assertThat(teams.getLastError()).isNull();
    }

    @Test
    void lockoutsGoToEveryChannelThatAskedForThem() {
        slack.setNotifyLockouts(true);
        when(channels.findByEnabledTrueAndNotifyLockoutsTrue()).thenReturn(List.of(slack));

        notifier.accountLockedOut("sam@example.com", Instant.now().plusSeconds(900));
        notifier.addressLockedOut("198.51.100.7", Instant.now().plusSeconds(900));

        verify(client, times(2)).post(eq(ChannelProvider.SLACK), any(), any());
        verify(client, never()).post(eq(ChannelProvider.TEAMS), any(), any());
    }

    @Test
    void theTestCardSaysWhatTheChannelWillGet() {
        slack.setNotifyLockouts(true);

        notifier.sendTest(slack);

        ArgumentCaptor<AlertCard> card = ArgumentCaptor.forClass(AlertCard.class);
        verify(client).post(eq(ChannelProvider.SLACK), any(), card.capture());
        assertThat(card.getValue().title()).contains("Keys at Contoso is connected");
        assertThat(card.getValue().text()).contains("keys about to expire").contains("failed automatic replacements")
                .contains("sign-in lockouts");
    }
}
