package io.github.apikeyportal.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.apikeyportal.activity.ActivityService;
import io.github.apikeyportal.logging.AuditLog;
import io.github.apikeyportal.integration.ServiceIntegrationRepository;
import io.github.apikeyportal.invite.UserInvitationRepository;
import io.github.apikeyportal.key.ApiKeyRepository;
import io.github.apikeyportal.user.GroupNameAlreadyUsedException;
import io.github.apikeyportal.user.TestUsers;
import io.github.apikeyportal.user.UserAccountRepository;
import io.github.apikeyportal.user.UserGroup;
import io.github.apikeyportal.user.UserGroupRepository;

@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

    @Mock
    private UserGroupRepository groups;

    @Mock
    private UserAccountRepository accounts;

    @Mock
    private ApiKeyRepository keys;

    @Mock
    private ServiceIntegrationRepository integrations;

    @Mock
    private UserInvitationRepository invitations;

    @Mock
    private ActivityService activity;

    @Mock
    private AuditLog audit;

    private GroupService service;

    @BeforeEach
    void setUp() {
        service = new GroupService(groups, accounts, keys, integrations, invitations, activity, audit);
    }

    @Test
    void createsAGroupWithTheNameTrimmed() {
        when(groups.save(any(UserGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.create("  Platform team  ", TestUsers.superAdmin()).getName()).isEqualTo("Platform team");
    }

    @Test
    void refusesANameThatAlreadyExists() {
        when(groups.existsByNameIgnoreCase("Platform team")).thenReturn(true);

        assertThatThrownBy(() -> service.create("Platform team", TestUsers.superAdmin()))
                .isInstanceOf(GroupNameAlreadyUsedException.class);
        verify(groups, never()).save(any());
    }

    @Test
    void deletesAGroupNothingPointsAt() {
        UserGroup group = TestUsers.platformTeam();
        when(groups.findById(2L)).thenReturn(Optional.of(group));
        when(accounts.countByGroup_Id(2L)).thenReturn(0L);
        when(keys.countByGroup_Id(2L)).thenReturn(0L);
        when(integrations.countByGroup_Id(2L)).thenReturn(0L);

        service.delete(2L, TestUsers.superAdmin());

        verify(groups).delete(group);
    }

    @Test
    void refusesToDeleteAGroupThatStillHasMembers() {
        when(groups.findById(2L)).thenReturn(Optional.of(TestUsers.platformTeam()));
        when(accounts.countByGroup_Id(2L)).thenReturn(3L);

        assertThatThrownBy(() -> service.delete(2L, TestUsers.superAdmin()))
                .isInstanceOf(GroupNotEmptyException.class)
                .hasMessageContaining("3 members");
        verify(groups, never()).delete(any());
    }

    @Test
    void refusesToDeleteAGroupThatStillHoldsKeys() {
        // deleting here would leave the keys visible to nobody at all
        when(groups.findById(2L)).thenReturn(Optional.of(TestUsers.platformTeam()));
        when(accounts.countByGroup_Id(2L)).thenReturn(0L);
        when(keys.countByGroup_Id(2L)).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(2L, TestUsers.superAdmin()))
                .isInstanceOf(GroupNotEmptyException.class)
                .hasMessageContaining("1 key");
        verify(groups, never()).delete(any());
    }

    @Test
    void refusesToDeleteAGroupThatStillHoldsAnIntegration() {
        when(groups.findById(2L)).thenReturn(Optional.of(TestUsers.platformTeam()));
        when(accounts.countByGroup_Id(2L)).thenReturn(0L);
        when(keys.countByGroup_Id(2L)).thenReturn(0L);
        when(integrations.countByGroup_Id(2L)).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(2L, TestUsers.superAdmin()))
                .isInstanceOf(GroupNotEmptyException.class)
                .hasMessageContaining("2 integrations");
    }

    @Test
    void refusesToDeleteAGroupSomeoneIsStillInvitedInto() {
        // the invitation points at the group by id; deleting it would strand the link
        when(groups.findById(2L)).thenReturn(Optional.of(TestUsers.platformTeam()));
        when(accounts.countByGroup_Id(2L)).thenReturn(0L);
        when(keys.countByGroup_Id(2L)).thenReturn(0L);
        when(integrations.countByGroup_Id(2L)).thenReturn(0L);
        when(invitations.countPendingInGroup(eq(2L), any())).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(2L, TestUsers.superAdmin()))
                .isInstanceOf(GroupNotEmptyException.class)
                .hasMessageContaining("1 pending invitation");
        verify(groups, never()).delete(any());
    }

    @Test
    void deletingAGroupThatIsNotThereIsANotFound() {
        when(groups.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(99L, TestUsers.superAdmin())).isInstanceOf(GroupNotFoundException.class);
    }

    @Test
    void reportsWhatEachGroupHoldsInOnePass() {
        when(groups.findAllByOrderByNameAsc())
                .thenReturn(List.of(TestUsers.platformTeam(), TestUsers.group(3L, "The Okafors")));
        when(accounts.countByGroup()).thenReturn(List.<Object[]>of(new Object[] {2L, 4L}));
        when(keys.countByGroup()).thenReturn(List.<Object[]>of(new Object[] {2L, 7L}));
        when(integrations.countByGroup()).thenReturn(List.of());

        List<GroupUsage> usage = service.findAllWithUsage();

        assertThat(usage).hasSize(2);
        assertThat(usage.get(0).members()).isEqualTo(4);
        assertThat(usage.get(0).keys()).isEqualTo(7);
        assertThat(usage.get(0).isEmpty()).isFalse();
        // nothing counted for the second group, so it can be removed
        assertThat(usage.get(1).isEmpty()).isTrue();
    }
}
