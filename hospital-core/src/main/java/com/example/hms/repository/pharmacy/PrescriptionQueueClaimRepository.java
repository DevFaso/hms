package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.PrescriptionQueueClaim;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * G13 work-queue claims. Every write goes through a caller that holds the
 * prescription row lock first (plan rule 3).
 */
public interface PrescriptionQueueClaimRepository extends JpaRepository<PrescriptionQueueClaim, UUID> {

    /** The claim row of one prescription, active or expired. */
    Optional<PrescriptionQueueClaim> findByPrescription_Id(UUID prescriptionId);

    /** The claim rows of a queue page, with the holder, in one query. */
    @EntityGraph(attributePaths = "claimedBy")
    List<PrescriptionQueueClaim> findByPrescription_IdIn(Collection<UUID> prescriptionIds);
}
