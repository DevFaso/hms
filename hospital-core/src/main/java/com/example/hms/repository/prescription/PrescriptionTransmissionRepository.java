package com.example.hms.repository.prescription;

import com.example.hms.model.prescription.PrescriptionTransmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PrescriptionTransmissionRepository
    extends JpaRepository<PrescriptionTransmission, UUID> {

    List<PrescriptionTransmission> findByPrescription_IdOrderByCreatedAtDesc(UUID prescriptionId);
}
