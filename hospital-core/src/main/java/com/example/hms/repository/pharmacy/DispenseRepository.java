package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.Dispense;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DispenseRepository extends JpaRepository<Dispense, UUID> {

    Page<Dispense> findByPrescriptionId(UUID prescriptionId, Pageable pageable);

    Page<Dispense> findByPatientId(UUID patientId, Pageable pageable);

    Page<Dispense> findByPharmacyId(UUID pharmacyId, Pageable pageable);
}
