package com.example.hms.repository;

import com.example.hms.model.Staff;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StaffRepository extends JpaRepository<Staff, UUID> {
    @EntityGraph(attributePaths = {"user","department","assignment","assignment.role","hospital"})
    @Query("SELECT s FROM Staff s WHERE s.user.isDeleted = false")
    List<Staff> findAllExcludingDeletedUsers();

    @Query("SELECT s FROM Staff s WHERE s.user.username = :identifier OR s.licenseNumber = :identifier OR s.assignment.role.code = :identifier")
    java.util.Optional<Staff> findByUsernameOrLicenseOrRoleCode(@Param("identifier") String identifier);
    // Lookup staff by user email (excludes deleted users)
    @Query("SELECT s FROM Staff s WHERE LOWER(s.user.email) = LOWER(:email) AND s.user.isDeleted = false")
    List<Staff> findByUserEmail(@Param("email") String email);

    // Lookup staff by user phone number (excludes deleted users)
    @Query("SELECT s FROM Staff s WHERE s.user.phoneNumber = :phone AND s.user.isDeleted = false")
    List<Staff> findByUserPhoneNumber(@Param("phone") String phone);


    List<Staff> findByUserId(UUID userId);

    @Query("select s.licenseNumber from Staff s where s.user.id = :userId order by s.createdAt asc")
    Optional<String> findAnyLicenseByUserId(@Param("userId") UUID userId);

    Optional<Staff> findByIdAndActiveTrue(UUID id);
    boolean existsByIdAndActiveTrue(UUID id);

    boolean existsByIdAndHospital_IdAndActiveTrue(UUID id, UUID hospitalId);

    boolean existsByLicenseNumberAndUserId(String licenseNumber, UUID userId);
    boolean existsByLicenseNumber(String licenseNumber);

    @EntityGraph(attributePaths = {"user","department","assignment","assignment.role","hospital"})
    @Query("SELECT s FROM Staff s WHERE s.hospital.id = :hospitalId AND s.user.isDeleted = false")
    Page<Staff> findByHospitalIdExcludingDeletedUsers(@Param("hospitalId") UUID hospitalId, Pageable pageable);

    @EntityGraph(attributePaths = {"user","department","assignment","assignment.role","hospital"})
    @Query("SELECT s FROM Staff s WHERE s.hospital.id = :hospitalId AND s.active = true AND s.user.isDeleted = false")
    Page<Staff> findByHospitalIdAndActiveTrueExcludingDeletedUsers(@Param("hospitalId") UUID hospitalId, Pageable pageable);

    @EntityGraph(attributePaths = {"user","department","assignment","assignment.role","hospital"})
    @Query("SELECT s FROM Staff s WHERE s.hospital.id IN :hospitalIds AND s.user.isDeleted = false")
    List<Staff> findByHospitalIdInExcludingDeletedUsers(@Param("hospitalIds") Collection<UUID> hospitalIds);

    Optional<Staff> findByUserIdAndHospitalId(UUID userId, UUID hospitalId);

    Optional<Staff> findFirstByUserIdOrderByCreatedAtAsc(UUID userId);

    boolean existsByAssignment_Id(UUID assignmentId);

    @Query("select s.user.id from Staff s where lower(s.licenseNumber) = lower(:license)")
    Optional<UUID> findUserIdByLicense(@Param("license") String license);

    /**
     * Usernames of active staff holding a role at a hospital.
     *
     * <p>Added for critical-value escalation (P0 #5), which has to reach
     * somebody OTHER than the ordering provider once that provider has failed to
     * respond — the previous escalation re-notified the same person and then
     * went silent.
     */
    @Query("""
        SELECT u.username FROM Staff s
        JOIN s.user u
        JOIN s.assignment a
        JOIN a.role r
        WHERE s.hospital.id = :hospitalId
          AND s.active = true
          AND u.isDeleted = false
          AND u.isActive = true
          AND UPPER(r.code) = UPPER(:roleCode)
    """)
    List<String> findActiveUsernamesByHospitalAndRole(@Param("hospitalId") UUID hospitalId,
                                                      @Param("roleCode") String roleCode);

    @Query("""
        SELECT s FROM Staff s
        JOIN FETCH s.user u
        JOIN FETCH s.assignment a
        JOIN FETCH a.role r
        WHERE s.hospital.id = :hospitalId
          AND s.active = true
          AND u.isDeleted = false
          AND (:roleCode IS NULL OR UPPER(r.code) = UPPER(:roleCode))
          AND (
                LOWER(COALESCE(s.name, '')) = LOWER(:identifier)
             OR LOWER(CONCAT(COALESCE(u.firstName, ''), ' ', COALESCE(u.lastName, ''))) = LOWER(:identifier)
             OR LOWER(u.username) = LOWER(:identifier)
             OR LOWER(COALESCE(u.email, '')) = LOWER(:identifier)
          )
    """)
    List<Staff> findActiveByHospitalAndRoleAndIdentifier(@Param("hospitalId") UUID hospitalId,
                                                         @Param("roleCode") String roleCode,
                                                         @Param("identifier") String identifier);

    // ----- Original derived queries retained for internal/backward compatibility -----
    @EntityGraph(attributePaths = {"user","department","assignment","assignment.role","hospital"})
    Page<Staff> findByHospital_Id(UUID hospitalId, Pageable pageable);

    @EntityGraph(attributePaths = {"user","department","assignment","assignment.role","hospital"})
    Page<Staff> findByHospital_IdAndActiveTrue(UUID hospitalId, Pageable pageable);

    @EntityGraph(attributePaths = {"user","department","assignment","assignment.role","hospital"})
    List<Staff> findByHospital_IdIn(Collection<UUID> hospitalIds);

    // ── MVP 19: License expiry alerts ───────────────────────────
    @EntityGraph(attributePaths = {"user","department","hospital"})
    @Query("""
        SELECT s FROM Staff s
        WHERE s.hospital.id = :hospitalId
          AND s.active = true
          AND s.user.isDeleted = false
          AND s.licenseExpiryDate IS NOT NULL
          AND s.licenseExpiryDate <= :cutoff
    """)
    List<Staff> findByHospitalIdAndLicenseExpiringBefore(
        @Param("hospitalId") UUID hospitalId,
        @Param("cutoff") LocalDate cutoff);

    /**
     * Every hospital's expiring licences, for the nightly sweep (Tier 2 item
     * 40).
     *
     * <p>Deliberately NOT scoped to one hospital: the sweep runs on a
     * scheduler with no request context, so there is no active tenant to scope
     * by. It is the only unscoped read of this data, and it never returns a
     * row to a user — it turns rows into notifications addressed to the
     * administrators of the row's own hospital.
     */
    @EntityGraph(attributePaths = {"user", "hospital"})
    @Query("""
        SELECT s FROM Staff s
        WHERE s.active = true
          AND s.user.isDeleted = false
          AND s.licenseExpiryDate IS NOT NULL
          AND s.licenseExpiryDate <= :cutoff
        ORDER BY s.licenseExpiryDate ASC
    """)
    List<Staff> findAllWithLicenseExpiringBefore(@Param("cutoff") LocalDate cutoff);

    // ── Portal: active providers by hospital + department (for patient booking) ──
    @EntityGraph(attributePaths = {"user","department","assignment","assignment.role","hospital"})
    @Query("""
        SELECT s FROM Staff s
        WHERE s.hospital.id = :hospitalId
          AND s.department.id = :departmentId
          AND s.active = true
          AND s.user.isDeleted = false
          AND UPPER(s.assignment.role.code) IN ('ROLE_DOCTOR','ROLE_NURSE','ROLE_SPECIALIST')
        ORDER BY s.name ASC
    """)
    List<Staff> findActiveProvidersByHospitalAndDepartment(
        @Param("hospitalId") UUID hospitalId,
        @Param("departmentId") UUID departmentId);

    // ── MVP 3: Lab staff filtered by lab roles ──────────────────
    @EntityGraph(attributePaths = {"user","department","assignment","assignment.role","hospital"})
    @Query("""
        SELECT s FROM Staff s
        WHERE s.hospital.id = :hospitalId
          AND s.active = true
          AND s.user.isDeleted = false
          AND UPPER(s.assignment.role.code) IN :roleCodes
        ORDER BY s.name ASC
    """)
    Page<Staff> findLabStaffByHospitalId(
        @Param("hospitalId") UUID hospitalId,
        @Param("roleCodes") Collection<String> roleCodes,
        Pageable pageable);

}

