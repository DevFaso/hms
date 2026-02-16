package com.example.hms.repository;

import com.example.hms.model.PatientFamilyHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FamilyHistoryRepository extends JpaRepository<PatientFamilyHistory, UUID> {

    /**
     * Find all family histories for a patient, ordered by recorded date descending
     */
    List<PatientFamilyHistory> findByPatient_IdOrderByRecordedDateDesc(UUID patientId);

    /**
     * Find all family histories for a patient at a specific hospital
     */
    List<PatientFamilyHistory> findByPatient_IdAndHospital_IdOrderByRecordedDateDesc(
            UUID patientId, UUID hospitalId);

    /**
     * Find all active family histories for a patient
     */
    List<PatientFamilyHistory> findByPatient_IdAndActiveTrue(UUID patientId);

    /**
     * Find family histories by condition category
     */
    List<PatientFamilyHistory> findByPatient_IdAndConditionCategoryOrderByRecordedDateDesc(
            UUID patientId, String conditionCategory);

    /**
     * Find genetic conditions for a patient
     */
    List<PatientFamilyHistory> findByPatient_IdAndGeneticConditionTrueOrderByRecordedDateDesc(
            UUID patientId);

    /**
     * Find conditions requiring screening
     */
    List<PatientFamilyHistory> findByPatient_IdAndScreeningRecommendedTrueOrderByRecordedDateDesc(
            UUID patientId);

    /**
     * Find family histories by relationship type
     */
    List<PatientFamilyHistory> findByPatient_IdAndRelationshipOrderByRecordedDateDesc(
            UUID patientId, String relationship);

    /**
     * Find family histories by generation
     */
    List<PatientFamilyHistory> findByPatient_IdAndGenerationOrderByRecordedDateDesc(
            UUID patientId, Integer generation);

    /**
     * Count family history records for a patient
     */
    long countByPatient_Id(UUID patientId);

    /**
     * Check if patient has genetic conditions in family history
     */
    boolean existsByPatient_IdAndGeneticConditionTrue(UUID patientId);

    /**
     * Find cancer family histories
     */
    List<PatientFamilyHistory> findByPatient_IdAndIsCancerTrueOrderByRecordedDateDesc(UUID patientId);

    /**
     * Find cardiovascular family histories
     */
    List<PatientFamilyHistory> findByPatient_IdAndIsCardiovascularTrueOrderByRecordedDateDesc(
            UUID patientId);

    /**
     * Find diabetes family histories
     */
    List<PatientFamilyHistory> findByPatient_IdAndIsDiabetesTrueOrderByRecordedDateDesc(UUID patientId);

    /**
     * Find mental health family histories
     */
    List<PatientFamilyHistory> findByPatient_IdAndIsMentalHealthTrueOrderByRecordedDateDesc(
            UUID patientId);

    /**
     * Find by specific genetic marker
     */
    @Query("SELECT fh FROM PatientFamilyHistory fh WHERE fh.patient.id = :patientId " +
            "AND fh.geneticMarker LIKE %:marker% ORDER BY fh.recordedDate DESC")
    List<PatientFamilyHistory> findByPatientAndGeneticMarker(
            @Param("patientId") UUID patientId,
            @Param("marker") String marker);

    /**
     * Find clinically significant family histories
     */
    List<PatientFamilyHistory> findByPatient_IdAndClinicallySignificantTrueOrderByRecordedDateDesc(
            UUID patientId);
}
