package io.github.apikeyportal.mfa;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, Long> {

    long countByUserId(Long userId);

    Optional<MfaRecoveryCode> findByUserIdAndCodeHash(Long userId, String codeHash);

    void deleteByUserId(Long userId);
}
