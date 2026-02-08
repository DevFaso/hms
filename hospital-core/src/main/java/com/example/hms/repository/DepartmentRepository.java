package com.example.hms.repository;

import com.example.hms.model.Department;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DepartmentRepository extends JpaRepository<Department, UUID>, JpaSpecificationExecutor<Department> {

    /* ---------- Lookups ---------- */

    @Query("SELECT d FROM Department d WHERE d.hospital.id = :hospitalId ORDER BY d.name")
    List<Department> findByHospitalId(@Param("hospitalId") UUID hospitalId);

    /** Bulk fetch with hospital & head to avoid N+1 / lazy issues in list view */
    @Query("SELECT DISTINCT d FROM Department d LEFT JOIN FETCH d.hospital LEFT JOIN FETCH d.headOfDepartment")
    List<Department> findAllWithHospitalAndHead();

    Page<Department> findByHospitalId(UUID hospitalId, Pageable pageable);

    @Query("SELECT d FROM Department d JOIN FETCH d.hospital WHERE d.id = :id")
    Optional<Department> findByIdWithHospital(@Param("id") UUID id);

    @Query("SELECT d FROM Department d JOIN FETCH d.headOfDepartment WHERE d.id = :id")
    Optional<Department> findByIdWithHeadOfDepartment(@Param("id") UUID id);

    /** Case-insensitive unique name per hospital */
    boolean existsByNameIgnoreCaseAndHospitalId(String name, UUID hospitalId);

    Optional<Department> findByHospitalIdAndNameIgnoreCase(UUID hospitalId, String name);

     Optional<Department> findByHospitalIdAndCodeIgnoreCase(UUID hospitalId, String code);

    /* ---------- Head of Department helpers ---------- */

    boolean existsByHeadOfDepartmentId(UUID staffId);

    @Modifying
    @Transactional
    @Query("UPDATE Department d SET d.headOfDepartment = NULL WHERE d.headOfDepartment.id = :staffId")
    void clearHeadOfDepartment(@Param("staffId") UUID staffId);

    /** JPA update on association must assign the entity, not its id */
    @Modifying
    @Transactional
    @Query("""
        UPDATE Department d
        SET d.headOfDepartment = (SELECT s FROM Staff s WHERE s.id = :staffId)
        WHERE d.id = :departmentId
    """)
    void updateHeadOfDepartment(@Param("departmentId") UUID departmentId, @Param("staffId") UUID staffId);

    /** Optional convenience: check HoD within a hospital */
    @Query("""
        SELECT CASE WHEN COUNT(d) > 0 THEN TRUE ELSE FALSE END
        FROM Department d
        WHERE d.hospital.id = :hospitalId AND d.headOfDepartment.id = :staffId
    """)
    boolean existsHeadInHospital(@Param("hospitalId") UUID hospitalId, @Param("staffId") UUID staffId);

    /* ---------- Counts & guards ---------- */

    @Query("SELECT COUNT(d) FROM Department d WHERE d.hospital.id = :hospitalId")
    long countByHospitalId(@Param("hospitalId") UUID hospitalId);

    @Query("SELECT COUNT(s) > 0 FROM Staff s WHERE s.department.id = :departmentId")
    boolean hasStaffMembers(@Param("departmentId") UUID departmentId);

    @Query("SELECT COUNT(t) > 0 FROM Treatment t WHERE t.department.id = :departmentId")
    boolean hasTreatments(@Param("departmentId") UUID departmentId);

    boolean existsByIdAndHospitalId(UUID departmentId, UUID hospitalId);

    /* ---------- Search ---------- */

    @Query("""
        SELECT d
        FROM Department d
        WHERE LOWER(d.name) LIKE LOWER(CONCAT('%', :query, '%'))
    """)
    Page<Department> search(@Param("query") String query, Pageable pageable);

    /* ---------- Translations (if present on your entity) ---------- */

    @Query("SELECT d FROM Department d JOIN FETCH d.departmentTranslations WHERE d.id = :id")
    Optional<Department> findByIdWithTranslations(@Param("id") UUID id);

    boolean existsByHeadOfDepartment_Id(UUID staffId);
}
