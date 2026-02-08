package com.example.hms.repository;

import com.example.hms.enums.EducationCategory;
import com.example.hms.model.education.VisitEducationDocumentation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface VisitEducationDocumentationRepository extends JpaRepository<VisitEducationDocumentation, UUID> {

    List<VisitEducationDocumentation> findByEncounterIdOrderByCreatedAtDesc(UUID encounterId);

    List<VisitEducationDocumentation> findByPatientIdOrderByCreatedAtDesc(UUID patientId);

    List<VisitEducationDocumentation> findByPatientIdAndCategoryOrderByCreatedAtDesc(
        UUID patientId, 
        EducationCategory category
    );

    List<VisitEducationDocumentation> findByPatientIdAndRequiresFollowUpTrueOrderByCreatedAtDesc(
        UUID patientId
    );

    @Query("SELECT ved FROM VisitEducationDocumentation ved WHERE ved.patientId = :patientId AND " +
           "ved.createdAt BETWEEN :startDate AND :endDate ORDER BY ved.createdAt DESC")
    List<VisitEducationDocumentation> findByPatientAndDateRange(
        @Param("patientId") UUID patientId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );

    @Query("SELECT ved FROM VisitEducationDocumentation ved WHERE ved.staffId = :staffId AND " +
           "ved.createdAt >= :since ORDER BY ved.createdAt DESC")
    List<VisitEducationDocumentation> findRecentByProvider(
        @Param("staffId") UUID staffId,
        @Param("since") LocalDateTime since
    );

    @Query("SELECT COUNT(ved) > 0 FROM VisitEducationDocumentation ved WHERE ved.patientId = :patientId AND " +
           "ved.category = :category")
    boolean hasDiscussedCategory(@Param("patientId") UUID patientId, @Param("category") EducationCategory category);
}
