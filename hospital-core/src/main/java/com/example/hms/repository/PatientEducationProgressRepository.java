package com.example.hms.repository;

import com.example.hms.enums.EducationComprehensionStatus;
import com.example.hms.model.education.PatientEducationProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PatientEducationProgressRepository extends JpaRepository<PatientEducationProgress, UUID> {

    List<PatientEducationProgress> findByPatientIdOrderByLastAccessedAtDesc(UUID patientId);

    List<PatientEducationProgress> findByPatientIdAndResourceId(UUID patientId, UUID resourceId);

    Optional<PatientEducationProgress> findTopByPatientIdAndResourceIdOrderByCreatedAtDesc(
        UUID patientId, 
        UUID resourceId
    );

    List<PatientEducationProgress> findByPatientIdAndComprehensionStatus(
        UUID patientId, 
        EducationComprehensionStatus status
    );

    List<PatientEducationProgress> findByPatientIdAndNeedsClarificationTrue(UUID patientId);

    List<PatientEducationProgress> findByPatientIdAndConfirmedUnderstandingFalse(UUID patientId);

    @Query("SELECT pep FROM PatientEducationProgress pep WHERE pep.patientId = :patientId AND " +
           "pep.comprehensionStatus = 'IN_PROGRESS' ORDER BY pep.lastAccessedAt DESC")
    List<PatientEducationProgress> findInProgressResources(@Param("patientId") UUID patientId);

    @Query("SELECT pep FROM PatientEducationProgress pep WHERE pep.patientId = :patientId AND " +
           "pep.comprehensionStatus = 'COMPLETED' ORDER BY pep.completedAt DESC")
    List<PatientEducationProgress> findCompletedResources(@Param("patientId") UUID patientId);

    @Query("SELECT COUNT(pep) FROM PatientEducationProgress pep WHERE pep.resourceId = :resourceId AND " +
           "pep.comprehensionStatus = 'COMPLETED'")
    Long countCompletions(@Param("resourceId") UUID resourceId);

    @Query("SELECT COUNT(pep) FROM PatientEducationProgress pep WHERE pep.patientId = :patientId AND " +
           "pep.comprehensionStatus = 'COMPLETED'")
    Long countCompletedResources(@Param("patientId") UUID patientId);

    @Query("SELECT AVG(pep.rating) FROM PatientEducationProgress pep WHERE pep.resourceId = :resourceId AND " +
           "pep.rating IS NOT NULL")
    Double calculateAverageRating(@Param("resourceId") UUID resourceId);
}
