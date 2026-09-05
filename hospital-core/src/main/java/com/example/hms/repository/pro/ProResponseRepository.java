package com.example.hms.repository.pro;

import com.example.hms.model.pro.ProResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProResponseRepository extends JpaRepository<ProResponse, UUID> {

    /** The patient's trend for one instrument at one hospital, newest first. */
    @EntityGraph(attributePaths = "instrument")
    List<ProResponse> findByPatient_IdAndHospital_IdAndInstrument_CodeOrderByAdministeredAtDesc(
        UUID patientId, UUID hospitalId, String instrumentCode, Pageable pageable);

    /** Everything the patient answered, across hospitals — the portal's own history. */
    @EntityGraph(attributePaths = "instrument")
    List<ProResponse> findByPatient_IdOrderByAdministeredAtDesc(UUID patientId, Pageable pageable);

    /** "Has this plan been screened yet" — the cadence hook. */
    Optional<ProResponse> findFirstByCarePlan_IdAndInstrument_CodeOrderByAdministeredAtDesc(
        UUID carePlanId, String instrumentCode);

    /** Tenant-scoped read: a response from another hospital is a not-found, not a leak. */
    Optional<ProResponse> findByIdAndPatient_IdAndHospital_Id(UUID id, UUID patientId, UUID hospitalId);

    /**
     * Self-harm-positive responses nobody has acknowledged whose last
     * notification is older than the cutoff. {@code COALESCE} onto
     * {@code createdAt} so a row whose write-time notification failed
     * still enters the chain instead of waiting forever.
     */
    @Query("""
        SELECT r FROM ProResponse r
        WHERE r.criticalItemPositive = true
          AND r.acknowledgedAt IS NULL
          AND COALESCE(r.lastEscalationAt, r.createdAt) < :cutoff
        ORDER BY COALESCE(r.lastEscalationAt, r.createdAt) ASC
        """)
    List<ProResponse> findCriticalAwaitingEscalation(@Param("cutoff") LocalDateTime cutoff);
}
