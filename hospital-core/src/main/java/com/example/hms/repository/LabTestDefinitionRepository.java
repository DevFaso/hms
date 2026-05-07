package com.example.hms.repository;

import com.example.hms.enums.LabTestDefinitionApprovalStatus;
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

    /**
     * Search lab test definitions with all filters optional.
     *
     * <p><b>Postgres NULL-bind workaround.</b> The {@code :keyword} /
     * {@code :unit} / {@code :category} parameters are wrapped in
     * {@code CAST(:p AS string)} so Postgres can type-infer the
     * placeholder when the caller passes {@code null}. Without the cast,
     * an SQL like {@code lower('%' || ? || '%')} with {@code ?} bound as
     * {@code SQL NULL} resolves to type {@code bytea} on Postgres
     * (Hibernate's default {@code setNull} type), and the query fails
     * with {@code ERROR: function lower(bytea) does not exist} at parse
     * time — Postgres type-checks every branch of an OR-short-circuit
     * even when the leading {@code :keyword IS NULL} would skip the
     * concatenation at runtime. H2 is more lenient (treats NULL as
     * {@code varchar} by default) which is why the bug only surfaced on
     * UAT. Same fix pattern as {@code UserRepository#searchByCriteria}
     * and {@code HospitalRepository#findAllWithDepartments} — any
     * existing JPQL with {@code CONCAT('%', :param, '%')} where
     * {@code :param} can be null needs the same cast.</p>
     */
    @Query("""
        SELECT l FROM LabTestDefinition l
        WHERE (:keyword IS NULL OR (
                 LOWER(l.name) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                 OR LOWER(l.testCode) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                 OR LOWER(COALESCE(l.description, '')) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
              ))
          AND (:unit IS NULL OR LOWER(l.unit) = LOWER(CAST(:unit AS string)))
          AND (:category IS NULL OR LOWER(l.category) = LOWER(CAST(:category AS string)))
          AND (:active IS NULL OR l.active = :active)
          AND (:approvalStatus IS NULL OR l.approvalStatus = :approvalStatus)
    """)
    Page<LabTestDefinition> search(@Param("keyword") String keyword,
                                   @Param("unit") String unit,
                                   @Param("category") String category,
                                   @Param("active") Boolean active,
                                   @Param("approvalStatus") LabTestDefinitionApprovalStatus approvalStatus,
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

    // ── Dashboard count queries ──────────────────────────────────────────────

    /** Count definitions by approval status within a specific hospital. */
    long countByApprovalStatusAndHospital_Id(LabTestDefinitionApprovalStatus approvalStatus, UUID hospitalId);

    /** Count active definitions within a specific hospital. */
    long countByActiveTrueAndHospital_Id(UUID hospitalId);
}
