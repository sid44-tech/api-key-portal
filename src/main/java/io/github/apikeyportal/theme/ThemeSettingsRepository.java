package io.github.apikeyportal.theme;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ThemeSettingsRepository extends JpaRepository<ThemeSettings, Long> {

    /** There is at most one row; this is how everything reads it. */
    Optional<ThemeSettings> findFirstByOrderByIdAsc();
}
