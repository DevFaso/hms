package com.example.hms.repository;

import com.example.hms.model.InsurerPayment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Insurer claims plan PR 3 (V456): the payers' remittances. Every read names the hospital, so a payment of another
 * hospital is never found.
 */
public interface InsurerPaymentRepository extends JpaRepository<InsurerPayment, UUID> {

    Optional<InsurerPayment> findByIdAndHospitalId(UUID id, UUID hospitalId);

    Optional<InsurerPayment> findByHospitalIdAndClientRequestId(UUID hospitalId, UUID clientRequestId);

    /** The payment under a row lock: its allocations and its reversal are decided one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM InsurerPayment p WHERE p.id = :id AND p.hospitalId = :hospitalId")
    Optional<InsurerPayment> lockByIdAndHospitalId(@Param("id") UUID id, @Param("hospitalId") UUID hospitalId);

    /** Whether a payment of this payer, not reversed, already carries this reference (compared in lower case). */
    @Query("""
           SELECT COUNT(p) > 0 FROM InsurerPayment p
            WHERE p.hospitalId = :hospitalId
              AND p.payerId = :payerId
              AND p.status = com.example.hms.enums.InsurerPaymentStatus.RECORDED
              AND LOWER(TRIM(p.reference)) = :reference
           """)
    boolean existsRecordedReference(@Param("hospitalId") UUID hospitalId, @Param("payerId") UUID payerId,
                                    @Param("reference") String reference);

    /** One page of a hospital's insurer payments, the latest received first; the payer filter is optional. */
    @Query(value = "SELECT p " + SEARCH_FROM + " ORDER BY p.receivedDate DESC, p.recordedAt DESC, p.id DESC",
           countQuery = "SELECT COUNT(p) " + SEARCH_FROM)
    Page<InsurerPayment> searchAtHospital(@Param("hospitalId") UUID hospitalId,
                                          @Param("anyPayer") boolean anyPayer, @Param("payerId") UUID payerId,
                                          Pageable page);

    String SEARCH_FROM = """
          FROM InsurerPayment p
         WHERE p.hospitalId = :hospitalId
           AND (:anyPayer = true OR p.payerId = :payerId)
        """;

    /** The hospital's payments, not reversed, received on or before {@code asOf}. */
    @Query("""
           SELECT p FROM InsurerPayment p
            WHERE p.hospitalId = :hospitalId
              AND p.status = com.example.hms.enums.InsurerPaymentStatus.RECORDED
              AND p.receivedDate <= :asOf
           """)
    List<InsurerPayment> findRecordedAtHospitalBy(@Param("hospitalId") UUID hospitalId,
                                                  @Param("asOf") LocalDate asOf);
}
