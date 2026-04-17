package com.example.hms.repository;

import com.example.hms.model.PatientConsent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;


@Repository
public interface PatientConsentRepository extends JpaRepository<PatientConsent, UUID> {

    Optional<PatientConsent> findByPatientIdAndFromHospitalIdAndToHospitalId(UUID patientId, UUID fromHospitalId, UUID toHospitalId);

    @EntityGraph(attributePaths = {"patient", "fromHospital", "toHospital"})
    Page<PatientConsent> findAllByPatientId(UUID patientId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "fromHospital", "toHospital"})
    Page<PatientConsent> findAllByFromHospitalId(UUID fromHospitalId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "fromHospital", "toHospital"})
    Page<PatientConsent> findAllByToHospitalId(UUID toHospitalId, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"patient", "fromHospital", "toHospital"})
    Page<PatientConsent> findAll(Pageable pageable);
}
