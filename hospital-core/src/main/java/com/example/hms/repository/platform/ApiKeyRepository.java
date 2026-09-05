package com.example.hms.repository.platform;

import com.example.hms.enums.platform.ApiKeyStatus;
import com.example.hms.model.platform.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /** The verification lookup: hash of the presented key, active rows only. */
    Optional<ApiKey> findByKeyHashAndStatus(String keyHash, ApiKeyStatus status);

    /** The admin listing: one hospital's keys, newest first. */
    List<ApiKey> findByHospital_IdOrderByCreatedAtDesc(UUID hospitalId);
}
