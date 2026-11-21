package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.PharmacySale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PharmacySaleRepository extends JpaRepository<PharmacySale, UUID> {

    Page<PharmacySale> findByHospital_Id(UUID hospitalId, Pageable pageable);

    Page<PharmacySale> findByPharmacy_IdAndHospital_Id(UUID pharmacyId, UUID hospitalId, Pageable pageable);

    Page<PharmacySale> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId, Pageable pageable);

    /**
     * QA D1129 (round 20): whether a provider reference is already on a
     * completed mobile-money walk-in sale of the same hospital, compared without
     * case or spaces ({@code PaymentReferenceRegistry}). One hospital (QA D782).
     */
    @org.springframework.data.jpa.repository.Query("""
           SELECT COUNT(s) > 0
             FROM PharmacySale s
            WHERE s.hospital.id = :hospitalId
              AND LOWER(REPLACE(TRIM(s.referenceNumber), ' ', '')) = :reference
              AND s.paymentMethod = com.example.hms.enums.PharmacyPaymentMethod.MOBILE_MONEY
              AND s.status = com.example.hms.enums.PharmacySaleStatus.COMPLETED
           """)
    boolean existsLiveReference(@org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
                                @org.springframework.data.repository.query.Param("reference") String reference);

    /** Billing plan PR 11 (QA D2430): the counter sale a client request key already recorded at this hospital. */
    java.util.Optional<PharmacySale> findByHospital_IdAndClientRequestId(UUID hospitalId, UUID clientRequestId);

    /** QA D2676 (r65): the sale locked, so its returns and refunds are counted in turn. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT s FROM PharmacySale s WHERE s.id = :id")
    java.util.Optional<PharmacySale> findLockedById(@org.springframework.data.repository.query.Param("id") UUID id);

    /** Review of #1239: a sale read by the reconciliation, only when it is of that pharmacy. */
    java.util.Optional<PharmacySale> findByIdAndPharmacy_Id(UUID id, UUID pharmacyId);

    /** QA D2673 (r65): the sales of one pharmacy of the hospital in [from, to), oldest first. */
    @org.springframework.data.jpa.repository.Query("SELECT s FROM PharmacySale s WHERE s.hospital.id = :hospitalId "
         + "AND s.pharmacy.id = :pharmacyId AND s.saleDate >= :from AND s.saleDate < :to ORDER BY s.saleDate")
    java.util.List<PharmacySale> findForDay(@org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
                                            @org.springframework.data.repository.query.Param("pharmacyId") UUID pharmacyId,
                                            @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
                                            @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to);

    /** Billing plan PR 10 (QA D2672): the pharmacy money taken in one cash session. */
    java.util.List<PharmacySale> findByCashSessionIdOrderByCreatedAtAsc(java.util.UUID cashSessionId);

    /** Billing plan PR 10 (QA D2672): the pharmacy money taken in any of these sessions (the day report). */
    java.util.List<PharmacySale> findByCashSessionIdIn(java.util.Collection<java.util.UUID> cashSessionIds);
}
