package com.example.hms.repository;

import com.example.hms.model.MfaBackupCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface MfaBackupCodeRepository extends JpaRepository<MfaBackupCode, UUID> {

    /** The codes that sign in: unused, and not issued with an unconfirmed replacement (V183). */
    List<MfaBackupCode> findByUserIdAndUsedFalseAndPendingFalse(UUID userId);

    List<MfaBackupCode> findByUserIdAndPending(UUID userId, boolean pending);

    void deleteAllByUserId(UUID userId);

    /** Spends the code if it is still unused; 1 when this call spent it, 0 when another did first. */
    @Modifying
    @Query("update MfaBackupCode c set c.used = true, c.usedAt = :usedAt where c.id = :id and c.used = false")
    int markUsed(@Param("id") UUID id, @Param("usedAt") LocalDateTime usedAt);
}
