package com.example.hms.repository;

import com.example.hms.model.LabTestDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LabTestDefinitionRepository extends JpaRepository<LabTestDefinition, UUID> {
    boolean existsByName(String name);

    @Query("""
        SELECT l FROM LabTestDefinition l
        WHERE l.hospital.id = :hospitalId
          AND l.active = true
          AND (
                UPPER(l.testCode) = UPPER(:identifier)
             OR LOWER(l.name) = LOWER(:identifier)
          )
    """)
    Optional<LabTestDefinition> findActiveByHospitalIdAndIdentifier(@Param("hospitalId") UUID hospitalId,
                                                                     @Param("identifier") String identifier);

    List<LabTestDefinition> findByHospital_IdAndActiveTrue(UUID hospitalId);

    List<LabTestDefinition> findByHospitalIsNullAndActiveTrue();

    @Query("""
        SELECT l FROM LabTestDefinition l
        WHERE (:keyword IS NULL OR (
                 LOWER(l.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                 OR LOWER(l.testCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                 OR LOWER(COALESCE(l.description, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
              ))
          AND (:unit IS NULL OR LOWER(l.unit) = LOWER(:unit))
          AND (:category IS NULL OR LOWER(l.category) = LOWER(:category))
          AND (:active IS NULL OR l.active = :active)
    """)
    Page<LabTestDefinition> search(@Param("keyword") String keyword,
                                   @Param("unit") String unit,
                                   @Param("category") String category,
                                   @Param("active") Boolean active,
                                   Pageable pageable);

    @Query("""
        SELECT l FROM LabTestDefinition l
        WHERE l.active = true
            AND l.hospital IS NULL
            AND (
                UPPER(l.testCode) = UPPER(:identifier)
             OR LOWER(l.name) = LOWER(:identifier)
            )
    """)
    Optional<LabTestDefinition> findActiveGlobalByIdentifier(@Param("identifier") String identifier);

    @Query("""
        SELECT l FROM LabTestDefinition l
        WHERE l.active = true
            AND (
                UPPER(l.testCode) = UPPER(:identifier)
             OR LOWER(l.name) = LOWER(:identifier)
            )
    """)
    List<LabTestDefinition> findActiveByIdentifier(@Param("identifier") String identifier);

    Optional<LabTestDefinition> findByNameIgnoreCase(String name);
}
