package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.StockWitnessConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.UUID;

/** QA D2679 (V388): the confirmations witnesses gave for controlled-drug movements. */
public interface StockWitnessConfirmationRepository extends JpaRepository<StockWitnessConfirmation, UUID> {

    /**
     * An unexpired confirmation by this witness, for this performer, at this hospital. All three are part of
     * the question: a confirmation given to one colleague, or at another hospital, confirms nothing here.
     */
    boolean existsByWitnessUserIdAndPerformedByUserIdAndHospitalIdAndExpiresAtAfter(
        UUID witnessUserId, UUID performedByUserId, UUID hospitalId, LocalDateTime now);
}
