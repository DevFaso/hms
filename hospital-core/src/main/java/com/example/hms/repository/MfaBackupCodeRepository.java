package com.example.hms.repository;

import com.example.hms.model.MfaBackupCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MfaBackupCodeRepository extends JpaRepository<MfaBackupCode, UUID> {

    /** The codes that sign in: unused, and not issued with an unconfirmed replacement (V183). */
    List<MfaBackupCode> findByUserIdAndUsedFalseAndPendingFalse(UUID userId);

    List<MfaBackupCode> findByUserIdAndPending(UUID userId, boolean pending);

    void deleteAllByUserId(UUID userId);
}
