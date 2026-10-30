package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.PharmacyPayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PharmacyPaymentRepository extends JpaRepository<PharmacyPayment, UUID> {

    Page<PharmacyPayment> findByDispenseId(UUID dispenseId, Pageable pageable);

    Page<PharmacyPayment> findByPatientId(UUID patientId, Pageable pageable);

    Page<PharmacyPayment> findByDispenseIdAndHospital_Id(UUID dispenseId, UUID hospitalId, Pageable pageable);

    Page<PharmacyPayment> findByPatientIdAndHospital_Id(UUID patientId, UUID hospitalId, Pageable pageable);

    /** QA D204: the payment already recorded for a dispense, if any (a dispense is paid once). */
    java.util.Optional<PharmacyPayment> findFirstByDispense_IdOrderByCreatedAtAsc(UUID dispenseId);

    /** QA D843: which of these dispenses already carry a POSTED payment. */
    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT p.dispense.id FROM PharmacyPayment p "
         + "WHERE p.dispense.id IN :dispenseIds AND p.status = com.example.hms.enums.PharmacyPaymentStatus.POSTED")
    java.util.List<UUID> findPaidDispenseIds(
            @org.springframework.data.repository.query.Param("dispenseIds") java.util.Collection<UUID> dispenseIds);

    /**
     * QA D1032: the payment's rank among the hospital's pharmacy payments of
     * its year (every status: a voided payment keeps its number, as a voided
     * invoice keeps its own).
     */
    @org.springframework.data.jpa.repository.Query("SELECT COUNT(p) FROM PharmacyPayment p "
         + "WHERE p.hospital.id = :hospitalId AND p.createdAt >= :yearStart AND p.createdAt <= :createdAt")
    long countReceiptRank(@org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
                          @org.springframework.data.repository.query.Param("yearStart") java.time.LocalDateTime yearStart,
                          @org.springframework.data.repository.query.Param("createdAt") java.time.LocalDateTime createdAt);

    /** QA D1059: the pharmacy payments one cashier received in [{@code start}, {@code end}). */
    @org.springframework.data.jpa.repository.Query("SELECT p FROM PharmacyPayment p "
         + "WHERE p.receivedByUser.id = :cashierId AND (:hospitalId IS NULL OR p.hospital.id = :hospitalId) "
         + "AND p.createdAt >= :start AND p.createdAt < :end ORDER BY p.createdAt ASC")
    java.util.List<PharmacyPayment> findReceivedByCashier(
            @org.springframework.data.repository.query.Param("cashierId") UUID cashierId,
            @org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
            @org.springframework.data.repository.query.Param("start") java.time.LocalDateTime start,
            @org.springframework.data.repository.query.Param("end") java.time.LocalDateTime end);

    /** QA D843: whether a dispense already carries a POSTED payment. */
    boolean existsByDispense_IdAndStatus(UUID dispenseId, com.example.hms.enums.PharmacyPaymentStatus status);

    /** QA D404: the payments still counted on a dispense. */
    java.util.List<PharmacyPayment> findByDispense_IdAndStatus(UUID dispenseId,
            com.example.hms.enums.PharmacyPaymentStatus status);

    /**
     * QA D404: void every POSTED payment of a dispense in one conditional
     * UPDATE, in the cancelling transaction. Returns the rows voided.
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = false)
    @org.springframework.data.jpa.repository.Query("UPDATE PharmacyPayment p SET "
         + "p.status = com.example.hms.enums.PharmacyPaymentStatus.VOIDED, p.voidedAt = :now, "
         + "p.voidedBy = :voidedBy, p.voidReason = :reason, p.updatedAt = :now "
         + "WHERE p.dispense.id = :dispenseId AND p.status = com.example.hms.enums.PharmacyPaymentStatus.POSTED")
    int voidPostedForDispense(@org.springframework.data.repository.query.Param("dispenseId") UUID dispenseId,
                              @org.springframework.data.repository.query.Param("voidedBy") UUID voidedBy,
                              @org.springframework.data.repository.query.Param("reason") String reason,
                              @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);
}
