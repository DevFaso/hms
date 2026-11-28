package com.example.hms.repository;

import com.example.hms.enums.InsurerClaimBatchStatus;
import com.example.hms.model.InsurerClaimBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Insurer claims plan PR 2 (V455): the bordereaux of a hospital. Every read names the hospital, so a bordereau of
 * another hospital is never found.
 */
public interface InsurerClaimBatchRepository extends JpaRepository<InsurerClaimBatch, UUID> {

    Optional<InsurerClaimBatch> findByIdAndHospitalId(UUID id, UUID hospitalId);

    List<InsurerClaimBatch> findByIdInAndHospitalId(Collection<UUID> ids, UUID hospitalId);

    Optional<InsurerClaimBatch> findByHospitalIdAndClientRequestId(UUID hospitalId, UUID clientRequestId);

    /**
     * The bordereau under a row lock, held to the end of the transaction: every change to it, to its lines and to
     * their settlement is made under this lock, so two people never settle the same bordereau at once.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM InsurerClaimBatch b WHERE b.id = :id AND b.hospitalId = :hospitalId")
    Optional<InsurerClaimBatch> lockByIdAndHospitalId(@Param("id") UUID id, @Param("hospitalId") UUID hospitalId);

    /** Several bordereaux locked in id order, so two transactions never wait on each other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM InsurerClaimBatch b WHERE b.id IN :ids AND b.hospitalId = :hospitalId ORDER BY b.id ASC")
    List<InsurerClaimBatch> lockAllByIdInAndHospitalId(@Param("ids") Collection<UUID> ids,
                                                      @Param("hospitalId") UUID hospitalId);

    /**
     * One page of a hospital's bordereaux in {@code statuses}, newest first; the payer filter applies only when
     * {@code anyPayer} is false.
     */
    @Query(value = "SELECT b " + SEARCH_FROM + " ORDER BY b.createdAt DESC, b.id DESC",
           countQuery = "SELECT COUNT(b) " + SEARCH_FROM)
    Page<InsurerClaimBatch> searchAtHospital(@Param("hospitalId") UUID hospitalId,
                                             @Param("statuses") Collection<InsurerClaimBatchStatus> statuses,
                                             @Param("anyPayer") boolean anyPayer, @Param("payerId") UUID payerId,
                                             Pageable page);

    String SEARCH_FROM = """
          FROM InsurerClaimBatch b
         WHERE b.hospitalId = :hospitalId
           AND b.status IN :statuses
           AND (:anyPayer = true OR b.payerId = :payerId)
        """;

    /** The highest rank given to a bordereau of the hospital in {@code year}; 0 when none was numbered. */
    @Query("""
           SELECT COALESCE(MAX(b.numberSeq), 0)
             FROM InsurerClaimBatch b
            WHERE b.hospitalId = :hospitalId AND b.numberYear = :year
           """)
    int maxSeqAtHospital(@Param("hospitalId") UUID hospitalId, @Param("year") int year);

    /** The hospital's bordereaux in {@code statuses} sent on or before {@code asOf}: the receivables to age. */
    @Query("""
           SELECT b FROM InsurerClaimBatch b
            WHERE b.hospitalId = :hospitalId
              AND b.status IN :statuses
              AND b.submittedOn <= :asOf
            ORDER BY b.submittedOn ASC, b.id ASC
           """)
    List<InsurerClaimBatch> findSentAtHospitalBy(@Param("hospitalId") UUID hospitalId,
                                                 @Param("statuses") Collection<InsurerClaimBatchStatus> statuses,
                                                 @Param("asOf") LocalDate asOf);

    /** The hospital's bordereaux in {@code statuses} sent in [{@code from}, {@code to}]: a reconciliation period. */
    @Query("""
           SELECT b FROM InsurerClaimBatch b
            WHERE b.hospitalId = :hospitalId
              AND b.status IN :statuses
              AND b.submittedOn >= :from
              AND b.submittedOn <= :to
            ORDER BY b.submittedOn ASC, b.id ASC
           """)
    List<InsurerClaimBatch> findSentAtHospitalBetween(@Param("hospitalId") UUID hospitalId,
                                                      @Param("statuses") Collection<InsurerClaimBatchStatus> statuses,
                                                      @Param("from") LocalDate from, @Param("to") LocalDate to);
}
