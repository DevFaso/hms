package com.example.hms.repository.pharmacy;

import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PrescriptionRoutingDecisionRepository extends JpaRepository<PrescriptionRoutingDecision, UUID> {

    List<PrescriptionRoutingDecision> findByPrescriptionId(UUID prescriptionId);

    List<PrescriptionRoutingDecision> findByPrescriptionIdOrderByDecidedAtDesc(UUID prescriptionId);

    /** Decisions of a page of prescriptions, newest first — the work queue's last-action lookup. */
    List<PrescriptionRoutingDecision> findByPrescription_IdInOrderByDecidedAtDesc(List<UUID> prescriptionIds);

    List<PrescriptionRoutingDecision> findByDecidedForPatientIdOrderByDecidedAtDesc(UUID patientId);

    Page<PrescriptionRoutingDecision> findByPrescriptionId(UUID prescriptionId, Pageable pageable);

    Page<PrescriptionRoutingDecision> findByDecidedForPatientId(UUID patientId, Pageable pageable);

    /** T-59: decisions that are still pending past a given timestamp (used by the scheduler). */
    List<PrescriptionRoutingDecision> findByRoutingTypeAndStatusAndDecidedAtBefore(
            RoutingType routingType, RoutingDecisionStatus status, LocalDateTime before);

    /**
     * G8: the open decisions whose id starts with the short SMS reference
     * token (the first 8 hex characters of the UUID, lower-cased — see
     * SmsPartnerNotificationChannel#buildRefToken). Matched in the database
     * rather than by loading every pending decision repository-wide; the
     * caller still binds the result to the replying pharmacy's number.
     */
    @Query("""
            select d from PrescriptionRoutingDecision d
            where d.routingType = :routingType
              and d.status in :statuses
              and lower(cast(d.id as string)) like concat(:idPrefix, '%')
            """)
    List<PrescriptionRoutingDecision> findOpenByIdPrefix(
            @Param("routingType") RoutingType routingType,
            @Param("statuses") Collection<RoutingDecisionStatus> statuses,
            @Param("idPrefix") String idPrefix);
}
