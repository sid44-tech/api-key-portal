package com.sidbhat.apikeyportal.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.sidbhat.apikeyportal.key.ApiKeyRepository;
import com.sidbhat.apikeyportal.user.TestUsers;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExpiryReminderServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    @Mock
    private ApiKeyRepository keys;

    @Mock
    private ExpiryReminderRepository reminders;

    @Mock
    private ExpiryReminderMailer mailer;

    private ExpiryReminderService service;

    @BeforeEach
    void setUp() {
        service = new ExpiryReminderService(keys, reminders, mailer);
        when(mailer.isAvailable()).thenReturn(true);
        when(mailer.send(any(), any(), anyLong())).thenReturn(true);
        when(reminders.existsByApiKeyIdAndExpiresOnAndThresholdDays(anyLong(), any(), anyInt())).thenReturn(false);
    }

    private ApiKey keyExpiringIn(long id, int days) {
        ApiKey key = new ApiKey("Deploy", "GitHub", "ghp_x", TODAY.minusDays(300), TODAY.plusDays(days));
        key.assignTo(TestUsers.alex(), TestUsers.platformTeam());
        ReflectionTestUtils.setField(key, "id", id);
        return key;
    }

    private void sweepFinds(ApiKey... found) {
        when(keys.findExpiringBetween(TODAY, TODAY.plusDays(30))).thenReturn(List.of(found));
    }

    @Test
    void emailsTheOwnerForTheThresholdTheKeyIsInside() {
        ApiKey key = keyExpiringIn(1L, 6);
        sweepFinds(key);

        assertThat(service.sendDueReminders(TODAY)).isEqualTo(1);

        verify(mailer).send(key, ReminderThreshold.ONE_WEEK, 6);
        ArgumentCaptor<ExpiryReminder> recorded = ArgumentCaptor.forClass(ExpiryReminder.class);
        verify(reminders).save(recorded.capture());
        assertThat(recorded.getValue().getThresholdDays()).isEqualTo(7);
        assertThat(recorded.getValue().getExpiresOn()).isEqualTo(TODAY.plusDays(6));
        assertThat(recorded.getValue().getRecipientEmail()).isEqualTo("alex@example.com");
    }

    @Test
    void aReminderAlreadySentAtThatThresholdIsNotSentAgain() {
        ApiKey key = keyExpiringIn(1L, 6);
        sweepFinds(key);
        when(reminders.existsByApiKeyIdAndExpiresOnAndThresholdDays(1L, TODAY.plusDays(6), 7)).thenReturn(true);

        assertThat(service.sendDueReminders(TODAY)).isZero();
        verify(mailer, never()).send(any(), any(), anyLong());
    }

    @Test
    void movingIntoTheNextBandSendsTheNextReminder() {
        // the week reminder went out; now it's the day before
        ApiKey key = keyExpiringIn(1L, 1);
        sweepFinds(key);
        when(reminders.existsByApiKeyIdAndExpiresOnAndThresholdDays(1L, TODAY.plusDays(1), 7)).thenReturn(true);

        assertThat(service.sendDueReminders(TODAY)).isEqualTo(1);
        verify(mailer).send(key, ReminderThreshold.ONE_DAY, 1);
    }

    @Test
    void aKeyAddedLateGetsOnlyTheReminderForWhereItIsNotABacklog() {
        // five days left on day one: the week email, never the month or fortnight ones
        ApiKey key = keyExpiringIn(1L, 5);
        sweepFinds(key);

        service.sendDueReminders(TODAY);

        verify(mailer).send(eq(key), eq(ReminderThreshold.ONE_WEEK), anyLong());
        verify(mailer, never()).send(eq(key), eq(ReminderThreshold.ONE_MONTH), anyLong());
        verify(mailer, never()).send(eq(key), eq(ReminderThreshold.TWO_WEEKS), anyLong());
    }

    @Test
    void aFailedSendLeavesNoRecordSoItIsRetriedTomorrow() {
        ApiKey key = keyExpiringIn(1L, 6);
        sweepFinds(key);
        when(mailer.send(any(), any(), anyLong())).thenReturn(false);

        assertThat(service.sendDueReminders(TODAY)).isZero();
        verify(reminders, never()).save(any());
    }

    @Test
    void withNoMailServerTheSweepDoesNothingRatherThanMarkingRemindersSent() {
        when(mailer.isAvailable()).thenReturn(false);
        sweepFinds(keyExpiringIn(1L, 6));

        assertThat(service.sendDueReminders(TODAY)).isZero();
        verify(reminders, never()).save(any());
        verify(keys, never()).findExpiringBetween(any(), any());
    }

    @Test
    void severalKeysAreHandledIndependently() {
        ApiKey soon = keyExpiringIn(1L, 1);
        ApiKey later = keyExpiringIn(2L, 20);
        sweepFinds(soon, later);

        assertThat(service.sendDueReminders(TODAY)).isEqualTo(2);
        verify(mailer).send(soon, ReminderThreshold.ONE_DAY, 1);
        verify(mailer).send(later, ReminderThreshold.ONE_MONTH, 20);
    }
}
