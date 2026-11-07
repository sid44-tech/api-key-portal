package io.github.apikeyportal.appaccess;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppAccessTokenRepository extends JpaRepository<AppAccessToken, Long> {

    Optional<AppAccessToken> findByTokenHash(String tokenHash);

    List<AppAccessToken> findByGroup_IdOrderByNameAsc(Long groupId);

    List<AppAccessToken> findAllByOrderByNameAsc();
}
