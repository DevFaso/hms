package com.example.hms.repository.pharmacy;

import com.example.hms.enums.PharmacyClaimStatus;
import com.example.hms.model.pharmacy.PharmacyClaim;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PharmacyClaimRepository extends JpaRepository<PharmacyClaim, UUID> {

    Page<PharmacyClaim> findByDispenseId(UUID dispenseId, Pageable pageable);

    Page<PharmacyClaim> findByPatientId(UUID patientId, Pageable pageable);

    Page<PharmacyClaim> findByDispenseIdAndHospitalId(UUID dispenseId, UUID hospitalId, Pageable pageable);

    Page<PharmacyClaim> findByPatientIdAndHospitalId(UUID patientId, UUID hospitalId, Pageable pageable);

    Page<PharmacyClaim> findByHospitalId(UUID hospitalId, Pageable pageable);

    Page<PharmacyClaim> findByHospitalIdAndClaimStatus(UUID hospitalId,
                                                        PharmacyClaimStatus status,
                                                        Pageable pageable);

    List<PharmacyClaim> findByHospitalIdAndClaimStatusIn(UUID hospitalId,
                                                         List<PharmacyClaimStatus> statuses);

    /**
     * QA D1613: whether a dispense already carries a live claim (any status but
     * REJECTED). A rejected claim is the one a resubmission replaces.
     */
    boolean existsByDispenseIdAndClaimStatusNot(UUID dispenseId, PharmacyClaimStatus status);

    /**
     * Review of #1284: what the live (non-REJECTED) pharmacy claims of a patient at a hospital asked of the payers
     * in [from, to), except those of one prescription (pass a random id to exclude none). They use the plan's
     * annual ceiling as the invoice shares do.
     */
    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(c.amount), 0) FROM PharmacyClaim c "
         + "WHERE c.patient.id = :patientId AND c.hospital.id = :hospitalId "
         + "AND c.claimStatus <> com.example.hms.enums.PharmacyClaimStatus.REJECTED "
         + "AND c.createdAt >= :from AND c.createdAt < :to AND c.dispense.prescription.id <> :excludedPrescriptionId")
    java.math.BigDecimal sumLiveForPatientBetween(
            @org.springframework.data.repository.query.Param("patientId") UUID patientId,
            @org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
            @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to,
            @org.springframework.data.repository.query.Param("excludedPrescriptionId") UUID excludedPrescriptionId);

    /** QA D1613: what the live (non-REJECTED) claims of one dispense ask of the payer. */
    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(c.amount), 0) FROM PharmacyClaim c "
         + "WHERE c.dispense.id = :dispenseId AND c.claimStatus <> com.example.hms.enums.PharmacyClaimStatus.REJECTED")
    java.math.BigDecimal sumLiveForDispense(
            @org.springframework.data.repository.query.Param("dispenseId") UUID dispenseId);

    /**
     * Policy rule 6 (review of #1244): the claims of one dispense in the given states. The counter no longer adds
     * up "claims sent": a pharmacist could create and submit one alone and hand the fill over on it. Each claim is
     * read, and {@code FillClaimCover} decides whether somebody else stands behind it.
     */
    List<PharmacyClaim> findByDispenseIdAndClaimStatusIn(UUID dispenseId,
                                                         java.util.Collection<PharmacyClaimStatus> statuses);
}
