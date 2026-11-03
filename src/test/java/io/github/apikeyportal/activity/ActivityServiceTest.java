package io.github.apikeyportal.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import io.github.apikeyportal.logging.AuditLog;
import io.github.apikeyportal.user.TestUsers;
import io.github.apikeyportal.user.UserAccount;

@ExtendWith(MockitoExtension.class)
class ActivityServiceTest {

    @Mock
    private ActivityEventRepository repository;

    @Mock
    private AuditLog audit;

    private ActivityService service;

    private final Pageable firstPage = PageRequest.of(0, 20);

    @BeforeEach
    void setUp() {
        service = new ActivityService(repository, audit);
    }

    private ActivityEvent recorded() {
        ArgumentCaptor<ActivityEvent> saved = ArgumentCaptor.forClass(ActivityEvent.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }

    private void stubSave() {
        when(repository.save(any(ActivityEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void aKeyCreatedThroughAnIntegrationRecordsWhoAndWhatKind() {
        stubSave();

        service.recordKeyCreated(TestUsers.alex(), 42L, "Azure AD", "Reporting service",
                KeyOrigin.provisionedBy("Azure AD production"));

        ActivityEvent event = recorded();
        assertThat(event.getAction()).isEqualTo(ActivityAction.KEY_CREATED);
        assertThat(event.getActorEmail()).isEqualTo("alex@example.com");
        assertThat(event.getActorName()).isEqualTo("Alex Carter");
        assertThat(event.getTargetName()).isEqualTo("Azure AD / Reporting service");
        assertThat(event.getKeySource()).isEqualTo(KeySource.PROVISIONED);
        assertThat(event.getDetail()).isEqualTo("Azure AD production");
    }

    @Test
    void aManuallyEnteredKeyIsRecordedAsSuch() {
        stubSave();

        service.recordKeyCreated(TestUsers.sam(), 7L, "GitHub", "CI token", KeyOrigin.manual());

        assertThat(recorded().getKeySource()).isEqualTo(KeySource.MANUAL);
        assertThat(recorded().getDetail()).isNull();
    }

    @Test
    void theEventCarriesTheActorsGroupSoTheirAdminCanSeeIt() {
        stubSave();

        service.recordKeyCreated(TestUsers.alex(), 1L, "GitHub", "CI token", KeyOrigin.manual());

        ActivityEvent event = recorded();
        assertThat(event.getGroupId()).isEqualTo(TestUsers.platformTeam().getId());
        assertThat(event.getGroupName()).isEqualTo("Platform team");
    }

    @Test
    void aRevealRecordsWhoSawTheValueAndHowTheyTookIt() {
        stubSave();

        service.recordKeyRevealed(TestUsers.alex(), 42L, "GitHub", "CI token", RevealMethod.COPIED);

        ActivityEvent event = recorded();
        assertThat(event.getAction()).isEqualTo(ActivityAction.KEY_REVEALED);
        assertThat(event.getActorEmail()).isEqualTo("alex@example.com");
        assertThat(event.getTargetName()).isEqualTo("GitHub / CI token");
        assertThat(event.getDetail()).isEqualTo("Copied to the clipboard");
        // a reveal is of an existing key, so the history can still link to it
        assertThat(event.isKeyEvent()).isTrue();
    }

    @Test
    void anEditRecordsWhatChanged() {
        stubSave();

        service.recordKeyUpdated(TestUsers.alex(), 42L, "GitHub", "CI token", "Changed value, expiry date");

        ActivityEvent event = recorded();
        assertThat(event.getAction()).isEqualTo(ActivityAction.KEY_UPDATED);
        assertThat(event.getDetail()).isEqualTo("Changed value, expiry date");
        assertThat(event.isKeyEvent()).isTrue();
    }

    @Test
    void aDeletedKeyIsNotLinkedToBecauseItIsGone() {
        stubSave();

        service.recordKeyDeleted(TestUsers.alex(), 42L, "GitHub", "CI token");

        assertThat(recorded().isKeyEvent()).isFalse();
    }

    @Test
    void anUnreadableRevealHintIsTakenAsAPlainReveal() {
        // an old page still open, or somebody calling the endpoint by hand
        assertThat(RevealMethod.fromParameter(null)).isEqualTo(RevealMethod.SHOWN);
        assertThat(RevealMethod.fromParameter("nonsense")).isEqualTo(RevealMethod.SHOWN);
        assertThat(RevealMethod.fromParameter(" Copied ")).isEqualTo(RevealMethod.COPIED);
    }

    @Test
    void aSuperAdminSeesEveryGroup() {
        when(repository.findAllByOrderByOccurredAtDesc(firstPage)).thenReturn(Page.empty(firstPage));

        service.findVisibleTo(TestUsers.superAdmin(), firstPage);

        verify(repository).findAllByOrderByOccurredAtDesc(firstPage);
        verify(repository, never()).findByGroupIdOrderByOccurredAtDesc(any(), any());
    }

    @Test
    void aGroupAdminSeesOnlyTheirOwnGroup() {
        when(repository.findByGroupIdOrderByOccurredAtDesc(2L, firstPage)).thenReturn(Page.empty(firstPage));

        service.findVisibleTo(TestUsers.alex(), firstPage);

        verify(repository).findByGroupIdOrderByOccurredAtDesc(2L, firstPage);
        verify(repository, never()).findAllByOrderByOccurredAtDesc(any());
    }

    @Test
    void aMemberCannotReadTheHistoryAtAll() {
        assertThatThrownBy(() -> service.findVisibleTo(TestUsers.sam(), firstPage))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).findAllByOrderByOccurredAtDesc(any());
        verify(repository, never()).findByGroupIdOrderByOccurredAtDesc(any(), any());
    }

    @Test
    void anAdminWithNoGroupSeesNothingRatherThanEverything() {
        UserAccount groupless = TestUsers.account(9L, "nobody@example.com", "No Group",
                io.github.apikeyportal.user.UserRole.GROUP_ADMIN, null);

        assertThat(service.findVisibleTo(groupless, firstPage)).isEmpty();
        verify(repository, never()).findAllByOrderByOccurredAtDesc(any());
    }

    @Test
    void canViewHistoryMatchesWhoTheServiceLetsIn() {
        assertThat(ActivityService.canViewHistory(TestUsers.superAdmin())).isTrue();
        assertThat(ActivityService.canViewHistory(TestUsers.alex())).isTrue();
        assertThat(ActivityService.canViewHistory(TestUsers.sam())).isFalse();
    }
}
