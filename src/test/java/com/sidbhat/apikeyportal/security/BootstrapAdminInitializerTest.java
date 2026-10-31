package com.sidbhat.apikeyportal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.sidbhat.apikeyportal.integration.ServiceIntegrationRepository;
import com.sidbhat.apikeyportal.key.ApiKeyRepository;
import com.sidbhat.apikeyportal.user.TestUsers;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountRepository;
import com.sidbhat.apikeyportal.user.UserGroup;
import com.sidbhat.apikeyportal.user.UserGroupRepository;
import com.sidbhat.apikeyportal.user.UserRole;

@ExtendWith(MockitoExtension.class)
class BootstrapAdminInitializerTest {

    @Mock
    private UserAccountRepository accounts;

    @Mock
    private UserGroupRepository groups;

    @Mock
    private ApiKeyRepository keys;

    @Mock
    private ServiceIntegrationRepository integrations;

    /** The real encoder, so the hashing the portal actually does is under test. */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private BootstrapAdminInitializer initializer(String password, String passwordHash) {
        return new BootstrapAdminInitializer(
                new SecurityProperties("admin@example.com", password, passwordHash, "Administrators"),
                accounts, groups, keys, integrations, passwordEncoder, null);
    }

    private void emptyPortal() {
        when(accounts.count()).thenReturn(0L);
        when(groups.findByNameIgnoreCase("Administrators")).thenReturn(Optional.empty());
        when(groups.save(any(UserGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        when(accounts.save(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        when(accounts.findAllByOrderByDisplayNameAsc()).thenReturn(List.of());
    }

    private UserAccount seeded() {
        ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
        verify(accounts).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void seedsTheSuperAdminWithTheDefaultPasswordAndMakesThemChangeIt() {
        emptyPortal();

        initializer("changeme", "").bootstrap();

        UserAccount admin = seeded();
        assertThat(admin.getEmail()).isEqualTo("admin@example.com");
        assertThat(admin.getRole()).isEqualTo(UserRole.SUPER_ADMIN);
        assertThat(admin.getGroup().getName()).isEqualTo("Administrators");
        assertThat(passwordEncoder.matches("changeme", admin.getPasswordHash())).isTrue();
        // the default is in the README, so it cannot be left in place
        assertThat(admin.isMustChangePassword()).isTrue();
    }

    @Test
    void storesOnlyAHashOfTheDefaultPassword() {
        emptyPortal();

        initializer("changeme", "").bootstrap();

        assertThat(seeded().getPasswordHash()).startsWith("$2").doesNotContain("changeme");
    }

    @Test
    void aConfiguredHashWinsAndForcesNoChange() {
        emptyPortal();

        initializer("changeme", "$2a$10$a-bcrypt-hash").bootstrap();

        UserAccount admin = seeded();
        assertThat(admin.getPasswordHash()).isEqualTo("$2a$10$a-bcrypt-hash");
        // somebody picked this one deliberately, so there is nothing to replace
        assertThat(admin.isMustChangePassword()).isFalse();
    }

    @Test
    void seedsNothingOnceThePortalHasAnAccount() {
        when(accounts.count()).thenReturn(1L);
        when(accounts.findAllByOrderByDisplayNameAsc()).thenReturn(List.of());

        initializer("changeme", "").bootstrap();

        verify(accounts, never()).save(any());
    }

    @Test
    void handsKeysThatPredateAccountsToTheSeededAdmin() {
        UserAccount admin = TestUsers.superAdmin();
        when(accounts.count()).thenReturn(0L);
        when(groups.findByNameIgnoreCase("Administrators")).thenReturn(Optional.of(admin.getGroup()));
        when(accounts.save(any(UserAccount.class))).thenReturn(admin);
        when(accounts.findAllByOrderByDisplayNameAsc()).thenReturn(List.of(admin));
        when(keys.claimOwnerless(admin, admin.getGroup())).thenReturn(3);
        when(integrations.claimUngrouped(admin.getGroup())).thenReturn(1);

        initializer("changeme", "$2a$10$a-bcrypt-hash").bootstrap();

        verify(keys).claimOwnerless(admin, admin.getGroup());
        verify(integrations).claimUngrouped(admin.getGroup());
    }
}
