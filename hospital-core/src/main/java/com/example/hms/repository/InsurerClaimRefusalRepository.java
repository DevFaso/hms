package com.example.hms.repository;

import com.example.hms.model.InsurerClaimRefusal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Insurer claims plan PR 3 (V458): the payers' refusals of bordereau lines. Every read names the hospital.
 */
public interface InsurerClaimRefusalRepository extends JpaRepository<InsurerClaimRefusal, UUID> {

    /** The refusals of these lines at one hospital, oldest first: the history shown under each line. */
    List<InsurerClaimRefusal> findByClaimLineIdInAndHospitalIdOrderByRecordedAtAscIdAsc(Collection<UUID> claimLineIds,
                                                                                        UUID hospitalId);

    Optional<InsurerClaimRefusal> findByHospitalIdAndClientRequestId(UUID hospitalId, UUID clientRequestId);

    boolean existsByClaimLineIdInAndHospitalId(Collection<UUID> claimLineIds, UUID hospitalId);

    /**
     * What was re-billed to the patient of one share at one hospital by {@code at}: the part of the share that had
     * already left the invoice's payer share at that moment (a receipt printed for an earlier payment).
     */
    @Query("""
           SELECT COALESCE(SUM(r.amount), 0) FROM InsurerClaimRefusal r
            WHERE r.shareId = :shareId
              AND r.hospitalId = :hospitalId
              AND r.resolution = com.example.hms.enums.ClaimRefusalResolution.REBILL_PATIENT
              AND r.recordedAt <= :at
           """)
    BigDecimal sumRebilledOfShareBy(@Param("shareId") UUID shareId, @Param("hospitalId") UUID hospitalId,
                                    @Param("at") LocalDateTime at);
}
