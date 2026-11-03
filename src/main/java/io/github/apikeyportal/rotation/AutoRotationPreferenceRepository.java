package io.github.apikeyportal.rotation;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import io.github.apikeyportal.integration.ServiceProvider;

public interface AutoRotationPreferenceRepository extends JpaRepository<AutoRotationPreference, Long> {

    Optional<AutoRotationPreference> findByUserIdAndProvider(Long userId, ServiceProvider provider);
}
