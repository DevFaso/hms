package com.example.hms.repository.pharmacy;

import com.example.hms.enums.DispenseStatus;
import com.example.hms.model.pharmacy.Dispense;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface DispenseRepository extends JpaRepository<Dispense, UUID> {

    Page<Dispense> findByPrescriptionId(UUID prescriptionId, Pageable pageable);

    Page<Dispense> findByPatientId(UUID patientId, Pageable pageable);

    Page<Dispense> findByPharmacyId(UUID pharmacyId, Pageable pageable);

    /**
     * T-39: dispenses completed within a time window — used by the refill reminder scheduler
     * to find patients whose supply is running out.
     */
    List<Dispense> findByStatusAndDispensedAtBetween(
            DispenseStatus status, LocalDateTime from, LocalDateTime to);

    /**
     * Sum of dispensed quantities for a prescription, excluding the given status
     * (typically CANCELLED). Returns {@code BigDecimal.ZERO} when no rows match,
     * never {@code null}, thanks to the {@code COALESCE(..., 0)} in the query.
     */
    @Query("SELECT COALESCE(SUM(d.quantityDispensed), 0) FROM Dispense d "
         + "WHERE d.prescription.id = :prescriptionId AND d.status <> :excludedStatus")
    BigDecimal sumQuantityDispensedForPrescription(
            @Param("prescriptionId") UUID prescriptionId,
            @Param("excludedStatus") DispenseStatus excludedStatus);
}
