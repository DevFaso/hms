package com.example.hms.repository;

import com.example.hms.model.InsurerClaimLine;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Insurer claims plan PRs 2 and 3 (V455): the lines of the bordereaux. Every read and write names the hospital.
 */
public interface InsurerClaimLineRepository extends JpaRepository<InsurerClaimLine, UUID> {

    /** The lines of one bordereau at one hospital (a cancelled bordereau keeps its released lines). */
    List<InsurerClaimLine> findByBatchIdAndHospitalId(UUID batchId, UUID hospitalId);

    List<InsurerClaimLine> findByIdInAndHospitalId(Collection<UUID> ids, UUID hospitalId);

    /** The lines of these shares at one hospital that are on a bordereau not cancelled. */
    List<InsurerClaimLine> findByShareIdInAndHospitalIdAndReleasedFalse(Collection<UUID> shareIds, UUID hospitalId);

    /** What ties a line to its bordereau, share and invoice, read without loading (or locking) the line. */
    record Ref(UUID lineId, UUID batchId, UUID shareId, UUID invoiceId, UUID invoiceItemId) {
    }

    @Query("""
           SELECT new com.example.hms.repository.InsurerClaimLineRepository$Ref(
                      l.id, l.batchId, l.shareId, l.invoiceId, l.invoiceItemId)
             FROM InsurerClaimLine l
            WHERE l.id IN :ids AND l.hospitalId = :hospitalId AND l.released = false
           """)
    List<Ref> refsAtHospital(@Param("ids") Collection<UUID> ids, @Param("hospitalId") UUID hospitalId);

    /** The bordereaux, not cancelled, that hold a line of this share at one hospital. */
    @Query("""
           SELECT DISTINCT l.batchId FROM InsurerClaimLine l
            WHERE l.shareId = :shareId AND l.hospitalId = :hospitalId AND l.released = false
           """)
    List<UUID> liveBatchIdsOfShare(@Param("shareId") UUID shareId, @Param("hospitalId") UUID hospitalId);

    /** The lines under a row lock, in id order. The first read of these lines in the transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM InsurerClaimLine l WHERE l.id IN :ids AND l.hospitalId = :hospitalId ORDER BY l.id ASC")
    List<InsurerClaimLine> lockAllByIdInAndHospitalId(@Param("ids") Collection<UUID> ids,
                                                     @Param("hospitalId") UUID hospitalId);

    /** Takes one share's lines off one bordereau (a draft); a bulk delete runs at once. */
    @Modifying(flushAutomatically = true)
    @Query("""
           DELETE FROM InsurerClaimLine l
            WHERE l.batchId = :batchId AND l.shareId = :shareId AND l.hospitalId = :hospitalId
           """)
    int deleteShareFromBatch(@Param("batchId") UUID batchId, @Param("shareId") UUID shareId,
                             @Param("hospitalId") UUID hospitalId);

    /** Removes every line of one bordereau (a draft being deleted). */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM InsurerClaimLine l WHERE l.batchId = :batchId AND l.hospitalId = :hospitalId")
    int deleteByBatchAtHospital(@Param("batchId") UUID batchId, @Param("hospitalId") UUID hospitalId);

    /** Releases the lines of a cancelled bordereau: their acts can be claimed on another one. */
    @Modifying(flushAutomatically = true)
    @Query("""
           UPDATE InsurerClaimLine l SET l.released = true
            WHERE l.batchId = :batchId AND l.hospitalId = :hospitalId AND l.released = false
           """)
    int releaseByBatchAtHospital(@Param("batchId") UUID batchId, @Param("hospitalId") UUID hospitalId);

    /** The settlement of one bordereau: the sums of its lines' amounts. */
    record Sums(UUID batchId, BigDecimal claimed, BigDecimal paid, BigDecimal rebilled, BigDecimal writtenOff,
                BigDecimal writeOffPending) {

        /** Neither paid, re-billed nor written off. */
        public BigDecimal outstanding() {
            return claimed.subtract(paid).subtract(rebilled).subtract(writtenOff);
        }
    }

    /** The sums of the lines of these bordereaux at one hospital, one row per bordereau that has lines. */
    @Query("""
           SELECT new com.example.hms.repository.InsurerClaimLineRepository$Sums(
                      l.batchId, COALESCE(SUM(l.claimedAmount), 0), COALESCE(SUM(l.paidAmount), 0),
                      COALESCE(SUM(l.rebilledAmount), 0), COALESCE(SUM(l.writtenOffAmount), 0),
                      COALESCE(SUM(l.writeOffPendingAmount), 0))
             FROM InsurerClaimLine l
            WHERE l.batchId IN :batchIds AND l.hospitalId = :hospitalId
            GROUP BY l.batchId
           """)
    List<Sums> sumsOfBatchesAtHospital(@Param("batchIds") Collection<UUID> batchIds,
                                       @Param("hospitalId") UUID hospitalId);

    /**
     * The lines of one payer's bordereaux, sent and not cancelled, that still take a payment at one hospital: the
     * oldest bordereau first.
     */
    @Query("""
           SELECT l FROM InsurerClaimLine l, InsurerClaimBatch b
            WHERE b.id = l.batchId
              AND l.hospitalId = :hospitalId
              AND b.hospitalId = :hospitalId
              AND b.payerId = :payerId
              AND b.status IN (com.example.hms.enums.InsurerClaimBatchStatus.SUBMITTED,
                               com.example.hms.enums.InsurerClaimBatchStatus.PARTIALLY_SETTLED)
              AND l.released = false
              AND l.claimedAmount > l.paidAmount + l.rebilledAmount + l.writtenOffAmount + l.writeOffPendingAmount
            ORDER BY b.submittedOn ASC, b.id ASC, l.invoiceNumber ASC, l.id ASC
           """)
    List<InsurerClaimLine> findOpenOfPayerAtHospital(@Param("payerId") UUID payerId,
                                                     @Param("hospitalId") UUID hospitalId);
}
