package com.example.hms.repository.pharmacy;

import com.example.hms.enums.PharmacyRefundStatus;
import com.example.hms.model.pharmacy.PharmacyRefund;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** QA D2676 (r65, V311): pharmacy refunds; every read names the hospital. */
public interface PharmacyRefundRepository extends JpaRepository<PharmacyRefund, UUID> {

    /** The refund locked for a step (decide, pay, cancel), at the acting hospital. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PharmacyRefund r WHERE r.id = :id AND r.hospitalId = :hospitalId")
    Optional<PharmacyRefund> findLockedByIdAndHospitalId(@Param("id") UUID id, @Param("hospitalId") UUID hospitalId);

    Optional<PharmacyRefund> findByIdAndHospitalId(UUID id, UUID hospitalId);

    List<PharmacyRefund> findByHospitalIdAndStatusInOrderByCreatedAtAsc(UUID hospitalId,
                                                                        Collection<PharmacyRefundStatus> statuses);

    boolean existsByReturnTransactionIdAndStatusIn(UUID returnTransactionId, Collection<PharmacyRefundStatus> statuses);

    /** What a sale already gives back (live refunds: requested, approved or paid). */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM PharmacyRefund r WHERE r.saleId = :saleId AND r.status IN :statuses")
    BigDecimal sumForSale(@Param("saleId") UUID saleId, @Param("statuses") Collection<PharmacyRefundStatus> statuses);

    /** What a dispense already gives back (live refunds). */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM PharmacyRefund r WHERE r.dispenseId = :dispenseId "
         + "AND r.status IN :statuses")
    BigDecimal sumForDispense(@Param("dispenseId") UUID dispenseId,
                              @Param("statuses") Collection<PharmacyRefundStatus> statuses);

    /** Refunds paid out at one pharmacy of the hospital in [from, to): money out of the day. */
    @Query("SELECT r FROM PharmacyRefund r WHERE r.hospitalId = :hospitalId AND r.pharmacyId = :pharmacyId "
         + "AND r.status = com.example.hms.enums.PharmacyRefundStatus.PAID AND r.paidAt >= :from AND r.paidAt < :to "
         + "ORDER BY r.paidAt")
    List<PharmacyRefund> findPaidBetween(@Param("hospitalId") UUID hospitalId, @Param("pharmacyId") UUID pharmacyId,
                                         @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
