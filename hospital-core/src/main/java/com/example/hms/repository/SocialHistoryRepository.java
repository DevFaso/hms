package com.example.hms.repository;

import com.example.hms.model.PatientSocialHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SocialHistoryRepository extends JpaRepository<PatientSocialHistory, UUID> {

    /**
     * Find all social histories for a patient, ordered by recorded date descending
     */
    List<PatientSocialHistory> findByPatient_IdOrderByRecordedDateDesc(UUID patientId);

    /**
     * Find all social histories for a patient at a specific hospital
     */
    List<PatientSocialHistory> findByPatient_IdAndHospital_IdOrderByRecordedDateDesc(
            UUID patientId, UUID hospitalId);

    /**
     * Find the current active social history for a patient
     */
    Optional<PatientSocialHistory> findFirstByPatient_IdAndActiveTrueOrderByRecordedDateDesc(
            UUID patientId);

    /**
     * Find social history by patient and version number
     */
    Optional<PatientSocialHistory> findByPatient_IdAndVersionNumber(UUID patientId, Integer versionNumber);

    /**
     * Find all active social histories for a patient
     */
    List<PatientSocialHistory> findByPatient_IdAndActiveTrue(UUID patientId);

    /**
     * Count social history records for a patient
     */
    long countByPatient_Id(UUID patientId);

    /**
     * Check if patient has any active social history
     */
    boolean existsByPatient_IdAndActiveTrue(UUID patientId);

    /**
     * Find social histories by tobacco use status
     */
    List<PatientSocialHistory> findByPatient_IdAndTobaccoUseTrueOrderByRecordedDateDesc(UUID patientId);

    /**
     * Find social histories by alcohol use status
     */
    List<PatientSocialHistory> findByPatient_IdAndAlcoholUseTrueOrderByRecordedDateDesc(UUID patientId);

    /**
     * Find social histories by recorded staff member
     */
    List<PatientSocialHistory> findByRecordedBy_IdOrderByRecordedDateDesc(UUID staffId);
}
