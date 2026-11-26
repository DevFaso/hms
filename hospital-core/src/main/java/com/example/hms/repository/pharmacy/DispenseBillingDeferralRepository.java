package com.example.hms.repository.pharmacy;

import com.example.hms.enums.DispenseBillingDeferralKind;
import com.example.hms.enums.DispenseBillingDeferralStatus;
import com.example.hms.model.pharmacy.DispenseBillingDeferral;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Policy rule 6 (V345): « à facturer » deferrals; every read names the hospital. */
public interface DispenseBillingDeferralRepository extends JpaRepository<DispenseBillingDeferral, UUID> {

    /** The deferral locked for a step (decide, execute, cancel, review), at the acting hospital. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DispenseBillingDeferral d WHERE d.id = :id AND d.hospitalId = :hospitalId")
    Optional<DispenseBillingDeferral> findLockedByIdAndHospitalId(@Param("id") UUID id,
                                                                  @Param("hospitalId") UUID hospitalId);

    List<DispenseBillingDeferral> findByHospitalIdAndStatusInOrderByRequestedAtAsc(
            UUID hospitalId, Collection<DispenseBillingDeferralStatus> statuses);

    List<DispenseBillingDeferral> findByHospitalIdAndPrescriptionIdAndStatusInOrderByRequestedAtAsc(
            UUID hospitalId, UUID prescriptionId, Collection<DispenseBillingDeferralStatus> statuses);

    boolean existsByHospitalIdAndPrescriptionIdAndStatusIn(UUID hospitalId, UUID prescriptionId,
                                                           Collection<DispenseBillingDeferralStatus> statuses);

    /** The urgency overrides finance has not reviewed yet: its task list. */
    List<DispenseBillingDeferral> findByHospitalIdAndKindAndReviewedAtIsNullOrderByRequestedAtAsc(
            UUID hospitalId, DispenseBillingDeferralKind kind);

    /** The latest urgency override one person made for a prescription and finance has not reviewed yet. */
    Optional<DispenseBillingDeferral>
        findFirstByHospitalIdAndPrescriptionIdAndKindAndRequestedByAndReviewedAtIsNullOrderByRequestedAtDesc(
            UUID hospitalId, UUID prescriptionId, DispenseBillingDeferralKind kind, UUID requestedBy);

    /** QA D3985 (r77): a patient's deferrals in one state at a hospital (the desk's balance reads the executed ones). */
    List<DispenseBillingDeferral> findByHospitalIdAndPatientIdAndStatus(UUID hospitalId, UUID patientId,
                                                                         DispenseBillingDeferralStatus status);

    /** These deferrals, at the acting hospital (the reconciliation of a day). */
    List<DispenseBillingDeferral> findByHospitalIdAndIdIn(UUID hospitalId, Collection<UUID> ids);

    /** Review of #1284: a patient's deferrals of one kind at a hospital, asked for in [from, to). */
    List<DispenseBillingDeferral> findByHospitalIdAndPatientIdAndKindAndStatusInAndRequestedAtBetween(
            UUID hospitalId, UUID patientId, com.example.hms.enums.DispenseBillingDeferralKind kind,
            Collection<com.example.hms.enums.DispenseBillingDeferralStatus> statuses, java.time.LocalDateTime from,
            java.time.LocalDateTime to);
}
