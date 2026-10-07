package com.example.hms.repository.pharmacy;

import com.example.hms.enums.DispenseStatus;
import com.example.hms.enums.DispenseVerificationStatus;
import com.example.hms.enums.ReadyCancelReason;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Dispense;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DispenseRepository extends JpaRepository<Dispense, UUID> {

    Page<Dispense> findByPrescriptionId(UUID prescriptionId, Pageable pageable);

    /**
     * Roadmap row 4 / T-68 — lookup used by DispenseServiceImpl.createDispense
     * to short-circuit a replayed POST from the offline queue. Returns the
     * already-saved Dispense if the client-supplied idempotency key is on file,
     * empty otherwise. Backed by the partial UNIQUE index uq_disp_idempotency_key
     * (V94) so this is an indexed point-lookup even at table scale.
     */
    Optional<Dispense> findByIdempotencyKey(String idempotencyKey);

    Page<Dispense> findByPatientId(UUID patientId, Pageable pageable);

    /** Live dispenses of a page of prescriptions, newest first — the work queue's last-action lookup. */
    List<Dispense> findByPrescription_IdInAndStatusNotOrderByDispensedAtDesc(
            List<UUID> prescriptionIds, DispenseStatus excludedStatus);

    Page<Dispense> findByPharmacyId(UUID pharmacyId, Pageable pageable);

    /**
     * T-39: dispenses completed within a time window — used by the refill reminder scheduler
     * to find patients whose supply is running out.
     */
    List<Dispense> findByStatusAndDispensedAtBetween(
            DispenseStatus status, LocalDateTime from, LocalDateTime to);

    /**
     * The statuses that are not a fill (G15): a cancelled fill, and a fill
     * that is prepared and still waiting for collection. Pass to
     * {@link #sumQuantityDispensedForPrescription}.
     */
    List<DispenseStatus> NOT_A_FILL = List.of(DispenseStatus.CANCELLED, DispenseStatus.PENDING);

    /**
     * Sum of dispensed quantities for a prescription, excluding the given
     * statuses (always {@link #NOT_A_FILL} in production). Returns
     * {@code BigDecimal.ZERO} when no rows match, never {@code null}, thanks
     * to the {@code COALESCE(..., 0)} in the query.
     */
    @Query("SELECT COALESCE(SUM(d.quantityDispensed), 0) FROM Dispense d "
         + "WHERE d.prescription.id = :prescriptionId AND d.status NOT IN :excludedStatuses")
    BigDecimal sumQuantityDispensedForPrescription(
            @Param("prescriptionId") UUID prescriptionId,
            @Param("excludedStatuses") Collection<DispenseStatus> excludedStatuses);

    /* ── Ready for collection (G15) ─────────────────────────────────────── */

    /** True when the prescription has an open preparation (status PENDING). */
    boolean existsByPrescription_IdAndStatus(UUID prescriptionId, DispenseStatus status);

    /** The open preparation of one prescription, if any (at most one: V178). */
    Optional<Dispense> findFirstByPrescription_IdAndStatus(UUID prescriptionId, DispenseStatus status);

    /** The open preparations of a page of prescriptions, one query. */
    List<Dispense> findByPrescription_IdInAndStatus(Collection<UUID> prescriptionIds, DispenseStatus status);

    /**
     * The prescription a dispense belongs to, read as a scalar so a caller
     * can lock the prescription BEFORE it touches the dispense (lock order:
     * prescription, then dispense). Empty when the id does not exist.
     */
    @Query("SELECT d.prescription.id FROM Dispense d WHERE d.id = :id")
    Optional<UUID> findPrescriptionIdById(@Param("id") UUID id);

    /**
     * Hand-over: PENDING to COMPLETED in one conditional UPDATE. Returns the
     * number of rows changed: 0 when the row is no longer PENDING. Bulk JPQL
     * skips {@code @PreUpdate}, so {@code updatedAt} is set here. The caller
     * refreshes the one managed row afterwards; the context is not cleared.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("UPDATE Dispense d SET d.status = com.example.hms.enums.DispenseStatus.COMPLETED, "
         + "d.dispensedAt = :now, d.dispensedByUser = :handedOverBy, "
         + "d.verificationStatus = :verificationStatus, d.patientScanValue = :patientScanValue, "
         + "d.scanVerifiedAt = :scanVerifiedAt, d.updatedAt = :now "
         + "WHERE d.id = :id AND d.status = com.example.hms.enums.DispenseStatus.PENDING")
    int completePreparedFill(@Param("id") UUID id,
                             @Param("now") LocalDateTime now,
                             @Param("handedOverBy") User handedOverBy,
                             @Param("verificationStatus") DispenseVerificationStatus verificationStatus,
                             @Param("patientScanValue") String patientScanValue,
                             @Param("scanVerifiedAt") LocalDateTime scanVerifiedAt);

    /**
     * Cancel a preparation: PENDING to CANCELLED in one conditional UPDATE.
     * 0 when the row is no longer PENDING.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("UPDATE Dispense d SET d.status = com.example.hms.enums.DispenseStatus.CANCELLED, "
         + "d.cancelReason = :reason, d.updatedAt = :now "
         + "WHERE d.id = :id AND d.status = com.example.hms.enums.DispenseStatus.PENDING")
    int cancelPreparedFill(@Param("id") UUID id,
                           @Param("reason") ReadyCancelReason reason,
                           @Param("now") LocalDateTime now);

    /**
     * Claim the single reminder of a preparation: stamps
     * {@code readyReminderSentAt} only while the row is still PENDING and
     * unreminded, so two sweeps (or a hand-over racing the sweep) cannot
     * both send. 1 = this caller owns the send.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("UPDATE Dispense d SET d.readyReminderSentAt = :now, d.updatedAt = :now "
         + "WHERE d.id = :id AND d.status = com.example.hms.enums.DispenseStatus.PENDING "
         + "AND d.readyReminderSentAt IS NULL")
    int claimReadyReminder(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /** Reminder sweep: open preparations older than the cut-off and not yet reminded. */
    List<Dispense> findByStatusAndReadyReminderSentAtIsNullAndCreatedAtBefore(
            DispenseStatus status, LocalDateTime cutoff);
}
