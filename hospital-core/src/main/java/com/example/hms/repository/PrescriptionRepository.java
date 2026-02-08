package com.example.hms.repository;

import com.example.hms.model.Prescription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PrescriptionRepository extends JpaRepository<Prescription, UUID> {

    Page<Prescription> findByPatient_Id(UUID patientId, Pageable pageable);

    Page<Prescription> findByStaff_Id(UUID staffId, Pageable pageable);

    Page<Prescription> findByEncounter_Id(UUID encounterId, Pageable pageable);

    List<Prescription> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId);
}
