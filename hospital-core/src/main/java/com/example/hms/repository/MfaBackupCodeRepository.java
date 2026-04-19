package com.example.hms.repository;

import com.example.hms.model.MfaBackupCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MfaBackupCodeRepository extends JpaRepository<MfaBackupCode, UUID> {

    List<MfaBackupCode> findByUserIdAndUsedFalse(UUID userId);

    void deleteAllByUserId(UUID userId);
}
