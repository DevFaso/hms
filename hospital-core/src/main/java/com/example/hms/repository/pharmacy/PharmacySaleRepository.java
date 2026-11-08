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
}
