package com.example.hms.repository;

import com.example.hms.model.InsurerPaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Insurer claims plan PR 3 (V456): the parts of the payers' remittances put on bordereau lines. Every read names
 * the hospital.
 */
public interface InsurerPaymentAllocationRepository extends JpaRepository<InsurerPaymentAllocation, UUID> {

    /** Every allocation of one payment at one hospital, voided ones included, oldest first. */
    List<InsurerPaymentAllocation> findByPaymentIdAndHospitalIdOrderByAllocatedAtAscIdAsc(UUID paymentId,
                                                                                         UUID hospitalId);

    /** What one payment's allocations still in force hold of it. Zero when none. */
    @Query("""
           SELECT COALESCE(SUM(a.amount), 0) FROM InsurerPaymentAllocation a
            WHERE a.paymentId = :paymentId AND a.hospitalId = :hospitalId AND a.voidedAt IS NULL
           """)
    BigDecimal sumLiveOfPaymentAtHospital(@Param("paymentId") UUID paymentId, @Param("hospitalId") UUID hospitalId);

    /** What is allocated of one payment. */
    record Allocated(UUID paymentId, BigDecimal amount) {
    }

    /** The allocations still in force of these payments at one hospital, summed per payment. */
    @Query("""
           SELECT new com.example.hms.repository.InsurerPaymentAllocationRepository$Allocated(
                      a.paymentId, COALESCE(SUM(a.amount), 0))
             FROM InsurerPaymentAllocation a
            WHERE a.paymentId IN :paymentIds AND a.hospitalId = :hospitalId AND a.voidedAt IS NULL
            GROUP BY a.paymentId
           """)
    List<Allocated> sumLiveOfPaymentsAtHospital(@Param("paymentIds") Collection<UUID> paymentIds,
                                                @Param("hospitalId") UUID hospitalId);

    /** Whether any allocation, voided or not, was ever made on these lines at one hospital. */
    boolean existsByClaimLineIdInAndHospitalId(Collection<UUID> claimLineIds, UUID hospitalId);
}
