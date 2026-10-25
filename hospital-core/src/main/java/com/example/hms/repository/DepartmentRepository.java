package com.example.hms.repository;

import com.example.hms.model.Department;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
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

    /** QA D280: LEFT, so a department without a head is found too (an inner join answered 404 for it). */
    @Query("SELECT d FROM Department d LEFT JOIN FETCH d.headOfDepartment WHERE d.id = :id")
    Optional<Department> findByIdWithHeadOfDepartment(@Param("id") UUID id);

    /** Case-insensitive unique name per hospital */
    boolean existsByNameIgnoreCaseAndHospitalId(String name, UUID hospitalId);

    /** QA D281: a rename may not land on another department's name in the same hospital. */
    boolean existsByNameIgnoreCaseAndHospitalIdAndIdNot(String name, UUID hospitalId, UUID id);

    /** QA D281: a department code is unique per hospital, case-insensitively. */
    boolean existsByCodeIgnoreCaseAndHospitalId(String code, UUID hospitalId);

    boolean existsByCodeIgnoreCaseAndHospitalIdAndIdNot(String code, UUID hospitalId, UUID id);

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

    /**
     * QA D601: any clinical or operational row that points at this department.
     * Dev carries no foreign key on several of these tables, and treatments
     * cascade on delete, so the refusal cannot rely on a constraint violation.
     */
    @Query("SELECT COUNT(d) > 0 FROM Department d WHERE d.id = :departmentId AND ("
        + "EXISTS (SELECT 1 FROM Admission r0 WHERE r0.department.id = :departmentId) "
        + "OR EXISTS (SELECT 1 FROM AdmissionOrderSet r1 WHERE r1.department.id = :departmentId) "
        + "OR EXISTS (SELECT 1 FROM Appointment r2 WHERE r2.department.id = :departmentId) "
        + "OR EXISTS (SELECT 1 FROM AppointmentWaitlist r3 WHERE r3.department.id = :departmentId) "
        + "OR EXISTS (SELECT 1 FROM Encounter r4 WHERE r4.department.id = :departmentId) "
        + "OR EXISTS (SELECT 1 FROM GeneralReferral r5 WHERE r5.sourceDepartment.id = :departmentId) "
        + "OR EXISTS (SELECT 1 FROM GeneralReferral r6 WHERE r6.targetDepartment.id = :departmentId) "
        + "OR EXISTS (SELECT 1 FROM ImagingReport r7 WHERE r7.department.id = :departmentId) "
        + "OR EXISTS (SELECT 1 FROM LabInstrument r8 WHERE r8.department.id = :departmentId) "
        + "OR EXISTS (SELECT 1 FROM OnCallSchedule r9 WHERE r9.department.id = :departmentId) "
        + "OR EXISTS (SELECT 1 FROM Questionnaire r10 WHERE r10.department.id = :departmentId) "
        + "OR EXISTS (SELECT 1 FROM AppointmentSlot r11 WHERE r11.department.id = :departmentId) "
        + "OR EXISTS (SELECT 1 FROM PatientRecall r12 WHERE r12.department.id = :departmentId) "
        + "OR EXISTS (SELECT 1 FROM SessionTemplate r13 WHERE r13.department.id = :departmentId) "
        + "OR EXISTS (SELECT 1 FROM VisitType r14 WHERE r14.department.id = :departmentId) "
        + "OR EXISTS (SELECT 1 FROM StaffLeaveRequest r15 WHERE r15.department.id = :departmentId) "
        + "OR EXISTS (SELECT 1 FROM StaffShift r16 WHERE r16.department.id = :departmentId) "
        + "OR EXISTS (SELECT 1 FROM Treatment r17 WHERE r17.department.id = :departmentId) "
        + "OR EXISTS (SELECT 1 FROM Ward r18 WHERE r18.department.id = :departmentId) "
        + ")")
    boolean existsByClinicalReference(@Param("departmentId") UUID departmentId);

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

    /** QA D280: LEFT, so a department without a translation row is found too. */
    @Query("SELECT DISTINCT d FROM Department d LEFT JOIN FETCH d.departmentTranslations WHERE d.id = :id")
    Optional<Department> findByIdWithTranslations(@Param("id") UUID id);

    boolean existsByHeadOfDepartment_Id(UUID staffId);
}
