package com.sidbhat.apikeyportal.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.sidbhat.apikeyportal.activity.KeyOrigin;
import com.sidbhat.apikeyportal.activity.ActivityService;
import com.sidbhat.apikeyportal.user.TestUsers;
import com.sidbhat.apikeyportal.user.UserAccount;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private ApiKeyRepository repository;

    @Mock
    private ActivityService activity;

    private ApiKeyService service;

    private final UserAccount alex = TestUsers.alex();
    private final UserAccount sam = TestUsers.sam();

    @BeforeEach
    void setUp() {
        service = new ApiKeyService(repository, activity);
    }

    /** A key belonging to Alex, who shares a group with Sam. */
    private ApiKey alexKey(String value) {
        ApiKey key = new ApiKey("Deploy", "GitHub", value, LocalDate.now(), null);
        key.assignTo(alex, alex.getGroup());
        return key;
    }

    @Test
    void createTrimsInputAndDefaultsCreatedOnToToday() {
        when(repository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiKey key = service.create(new ApiKeyDetails("  Deploy  ", " GitHub ", " ghp_abc ", null, null, "  "), alex, KeyOrigin.manual());

        assertThat(key.getName()).isEqualTo("Deploy");
        assertThat(key.getServiceName()).isEqualTo("GitHub");
        assertThat(key.getKeyValue()).isEqualTo("ghp_abc");
        assertThat(key.getCreatedOn()).isEqualTo(LocalDate.now());
        assertThat(key.getExpiresOn()).isNull();
        assertThat(key.getNotes()).isNull();
    }

    @Test
    void createAssignsTheKeyToItsCreatorsGroup() {
        when(repository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiKey key = service.create(new ApiKeyDetails("Deploy", "GitHub", "ghp_abc", null, null, null), alex, KeyOrigin.manual());

        assertThat(key.getOwner()).isEqualTo(alex);
        assertThat(key.getGroup()).isEqualTo(alex.getGroup());
        assertThat(key.isOwnedBy(alex)).isTrue();
        assertThat(key.isOwnedBy(sam)).isFalse();
    }

    @Test
    void getByIdThrowsWhenMissing() {
        when(repository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(42L, alex)).isInstanceOf(ApiKeyNotFoundException.class);
    }

    @Test
    void groupMembersCanSeeEachOthersKeyMetadata() {
        when(repository.findById(1L)).thenReturn(Optional.of(alexKey("ghp_abc")));

        assertThat(service.getById(1L, sam).getName()).isEqualTo("Deploy");
    }

    @Test
    void someoneFromAnotherGroupCannotSeeTheKeyAtAll() {
        when(repository.findById(1L)).thenReturn(Optional.of(alexKey("ghp_abc")));

        assertThatThrownBy(() -> service.getById(1L, TestUsers.outsider()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void onlyTheOwnerCanRevealTheValue() {
        when(repository.findById(1L)).thenReturn(Optional.of(alexKey("ghp_secret")));

        assertThat(service.revealValue(1L, alex)).isEqualTo("ghp_secret");
    }

    @Test
    void groupMembersCannotRevealTheValue() {
        when(repository.findById(1L)).thenReturn(Optional.of(alexKey("ghp_secret")));

        assertThatThrownBy(() -> service.revealValue(1L, sam)).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).markRevealed(any(), any());
    }

    @Test
    void notEvenASuperAdminCanRevealSomeoneElsesValue() {
        when(repository.findById(1L)).thenReturn(Optional.of(alexKey("ghp_secret")));

        assertThatThrownBy(() -> service.revealValue(1L, TestUsers.superAdmin()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deleteRemovesExistingKey() {
        ApiKey key = alexKey("ghp_abc");
        when(repository.findById(1L)).thenReturn(Optional.of(key));

        service.delete(1L, alex);

        verify(repository).delete(key);
    }

    @Test
    void groupMembersCannotDeleteSomeoneElsesKey() {
        when(repository.findById(1L)).thenReturn(Optional.of(alexKey("ghp_abc")));

        assertThatThrownBy(() -> service.delete(1L, sam)).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).delete(any(ApiKey.class));
    }

    @Test
    void deleteOfMissingKeyDoesNothing() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(1L, alex)).isInstanceOf(ApiKeyNotFoundException.class);
        verify(repository, never()).delete(any(ApiKey.class));
    }

    @Test
    void updateReplacesValueWhenProvided() {
        ApiKey key = alexKey("ghp_old");
        when(repository.findById(1L)).thenReturn(Optional.of(key));

        service.update(1L, new ApiKeyDetails("Deploy", "GitHub", " ghp_new ", key.getCreatedOn(), null,
                "repo scope"), alex);

        assertThat(key.getKeyValue()).isEqualTo("ghp_new");
        assertThat(key.getNotes()).isEqualTo("repo scope");
    }

    @Test
    void updateKeepsExistingValueWhenBlank() {
        ApiKey key = alexKey("ghp_old");
        when(repository.findById(1L)).thenReturn(Optional.of(key));

        service.update(1L, new ApiKeyDetails("Deploy v2", "GitHub", "", key.getCreatedOn(), null, null), alex);

        assertThat(key.getName()).isEqualTo("Deploy v2");
        assertThat(key.getKeyValue()).isEqualTo("ghp_old");
    }

    @Test
    void groupMembersCannotEditSomeoneElsesKey() {
        ApiKey key = alexKey("ghp_old");
        when(repository.findById(1L)).thenReturn(Optional.of(key));

        assertThatThrownBy(() -> service.update(1L,
                new ApiKeyDetails("Hijacked", "GitHub", "ghp_new", key.getCreatedOn(), null, null), sam))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(key.getName()).isEqualTo("Deploy");
    }
}
