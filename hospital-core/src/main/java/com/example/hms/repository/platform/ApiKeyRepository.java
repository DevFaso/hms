package com.example.hms.repository.platform;

import com.example.hms.enums.platform.ApiKeyStatus;
import com.example.hms.model.platform.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /** The verification lookup: hash of the presented key, active rows only. */
    Optional<ApiKey> findByKeyHashAndStatus(String keyHash, ApiKeyStatus status);

    /** The admin listing: one hospital's keys, newest first. */
    List<ApiKey> findByHospital_IdOrderByCreatedAtDesc(UUID hospitalId);

    /**
     * The liveness stamp as a direct UPDATE: a bulk JPQL write executes
     * immediately and bypasses the {@code @Version} check, so two
     * concurrent requests on one key can never turn a VALID key into an
     * optimistic-lock 500 — last write wins, which is exactly right for
     * a bookkeeping timestamp.
     */
    @Modifying
    @Query("update ApiKey k set k.lastUsedAt = :now where k.id = :id")
    int stampLastUsed(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
