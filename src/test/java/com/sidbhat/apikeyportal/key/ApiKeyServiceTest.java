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

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private ApiKeyRepository repository;

    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        service = new ApiKeyService(repository);
    }

    @Test
    void createTrimsInputAndDefaultsCreatedOnToToday() {
        when(repository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiKey key = service.create("  Deploy  ", " GitHub ", " ghp_abc ", null, null, "  ");

        assertThat(key.getName()).isEqualTo("Deploy");
        assertThat(key.getServiceName()).isEqualTo("GitHub");
        assertThat(key.getKeyValue()).isEqualTo("ghp_abc");
        assertThat(key.getCreatedOn()).isEqualTo(LocalDate.now());
        assertThat(key.getExpiresOn()).isNull();
        assertThat(key.getNotes()).isNull();
    }

    @Test
    void getByIdThrowsWhenMissing() {
        when(repository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(42L)).isInstanceOf(ApiKeyNotFoundException.class);
    }

    @Test
    void deleteRemovesExistingKey() {
        ApiKey key = new ApiKey("Deploy", "GitHub", "ghp_abc", LocalDate.now(), null);
        when(repository.findById(1L)).thenReturn(Optional.of(key));

        service.delete(1L);

        verify(repository).delete(key);
    }

    @Test
    void deleteOfMissingKeyDoesNothing() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(ApiKeyNotFoundException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void updateReplacesValueWhenProvided() {
        ApiKey key = new ApiKey("Deploy", "GitHub", "ghp_old", LocalDate.now(), null);
        when(repository.findById(1L)).thenReturn(Optional.of(key));

        service.update(1L, "Deploy", "GitHub", " ghp_new ", key.getCreatedOn(), null, "repo scope");

        assertThat(key.getKeyValue()).isEqualTo("ghp_new");
        assertThat(key.getNotes()).isEqualTo("repo scope");
    }

    @Test
    void updateKeepsExistingValueWhenBlank() {
        ApiKey key = new ApiKey("Deploy", "GitHub", "ghp_old", LocalDate.now(), null);
        when(repository.findById(1L)).thenReturn(Optional.of(key));

        service.update(1L, "Deploy v2", "GitHub", "", key.getCreatedOn(), null, null);

        assertThat(key.getName()).isEqualTo("Deploy v2");
        assertThat(key.getKeyValue()).isEqualTo("ghp_old");
    }
}
