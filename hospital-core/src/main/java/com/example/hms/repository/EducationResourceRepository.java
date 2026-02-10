package com.example.hms.repository;

import com.example.hms.enums.EducationCategory;
import com.example.hms.enums.EducationResourceType;
import com.example.hms.model.education.EducationResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EducationResourceRepository extends JpaRepository<EducationResource, UUID> {

    List<EducationResource> findByIsActiveTrueOrderByCreatedAtDesc();

    List<EducationResource> findByCategoryAndIsActiveTrue(EducationCategory category);

    List<EducationResource> findByResourceTypeAndIsActiveTrue(EducationResourceType resourceType);

    List<EducationResource> findByIsWarningSignContentTrueAndIsActiveTrue();

    List<EducationResource> findByIsHighRiskRelevantTrueAndIsActiveTrue();

    List<EducationResource> findByHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(UUID hospitalId);

    List<EducationResource> findByCategoryAndHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(
        EducationCategory category,
        UUID hospitalId
    );

    List<EducationResource> findByResourceTypeAndHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(
        EducationResourceType resourceType,
        UUID hospitalId
    );

    List<EducationResource> findByPrimaryLanguageAndHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(
        String primaryLanguage,
        UUID hospitalId
    );

    @Query("SELECT er FROM EducationResource er WHERE er.isActive = true AND " +
           "(er.organizationId = :organizationId OR er.hospitalId = :hospitalId OR " +
           "(er.organizationId IS NULL AND er.hospitalId IS NULL)) " +
           "ORDER BY er.createdAt DESC")
    List<EducationResource> findResourcesForOrganizationOrHospital(
        @Param("organizationId") UUID organizationId,
        @Param("hospitalId") UUID hospitalId
    );

    @Query("SELECT er FROM EducationResource er WHERE er.isActive = true AND er.hospitalId = :hospitalId AND (" +
           "LOWER(er.title) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(er.description) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) " +
           "ORDER BY er.createdAt DESC")
    List<EducationResource> searchResources(
        @Param("searchTerm") String searchTerm,
        @Param("hospitalId") UUID hospitalId
    );

    @Query("SELECT er FROM EducationResource er WHERE er.isActive = true AND er.hospitalId = :hospitalId AND er.category = :category " +
           "ORDER BY er.viewCount DESC, er.averageRating DESC")
    List<EducationResource> findPopularResourcesByCategory(
        @Param("category") EducationCategory category,
        @Param("hospitalId") UUID hospitalId
    );

    @Query("SELECT er FROM EducationResource er JOIN er.tags t WHERE t = :tag AND er.isActive = true")
    List<EducationResource> findByTag(@Param("tag") String tag);
}
