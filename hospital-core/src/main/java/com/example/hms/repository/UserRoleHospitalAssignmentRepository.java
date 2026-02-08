package com.example.hms.repository;

import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public interface UserRoleHospitalAssignmentRepository extends JpaRepository<UserRoleHospitalAssignment, UUID>,
    JpaSpecificationExecutor<UserRoleHospitalAssignment> {

    long countByActiveTrue();

    long countByHospitalIsNull();

    long countByHospitalIsNullAndActiveTrue();

    /* ------------ Basic lookups ------------ */
    List<UserRoleHospitalAssignment> findByUserId(UUID userId);

    /** Prefer this over Optional for multi-row relation */
    List<UserRoleHospitalAssignment> findAllByUserId(UUID userId);

    Optional<UserRoleHospitalAssignment> findByUserIdAndHospitalId(UUID userId, UUID hospitalId);

    Optional<UserRoleHospitalAssignment> findByUserUsernameAndHospitalIdAndRoleCodeIgnoreCase(
        String username, UUID hospitalId, String roleCode);

    Optional<UserRoleHospitalAssignment> findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc(UUID userId);

    Optional<UserRoleHospitalAssignment> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<UserRoleHospitalAssignment> findFirstByUser_IdAndHospital_IdAndActiveTrue(UUID userId, UUID hospitalId);

    List<UserRoleHospitalAssignment> findByUser_IdAndActiveTrue(UUID userId);

    Optional<UserRoleHospitalAssignment> findFirstByUserIdAndRole_CodeIgnoreCaseAndActiveTrue(UUID userId, String roleCode);

    List<UserRoleHospitalAssignment> findByRoleId(UUID roleId);

    Optional<UserRoleHospitalAssignment> findFirstByHospitalIdAndRole_Name(UUID hospitalId, String roleName);

    Optional<UserRoleHospitalAssignment> findByUserIdAndHospitalIdAndRole_Name(UUID userId, UUID hospitalId, String roleName);

    @EntityGraph(attributePaths = {"user", "hospital", "role"})
    Optional<UserRoleHospitalAssignment> findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
        UUID userId, UUID hospitalId, String roleCode);

    /* Global role (hospital = null) helpers */
    Optional<UserRoleHospitalAssignment> findByUserIdAndRoleIdAndHospitalIsNull(UUID userId, UUID roleId);
    Optional<UserRoleHospitalAssignment> findFirstByUserIdAndRoleIdAndHospitalIsNull(UUID userId, UUID roleId);
    boolean existsByUserIdAndRoleIdAndHospitalIsNull(UUID userId, UUID roleId);
    boolean existsByUserIdAndRoleIdAndHospitalIsNullAndActiveTrue(UUID userId, UUID roleId);

    boolean existsByAssignmentCode(String assignmentCode);

    Optional<UserRoleHospitalAssignment> findByAssignmentCode(String assignmentCode);

    /* ------------ Existence checks ------------ */
    boolean existsByUserIdAndHospitalIdAndActiveTrue(UUID userId, UUID hospitalId);

    boolean existsByUserIdAndActiveTrue(UUID userId);

    boolean existsByUserIdAndHospitalIdAndRoleId(UUID userId, UUID hospitalId, UUID roleId);

    boolean existsByUserIdAndHospitalIdAndRoleIdAndActiveTrue(UUID userId, UUID hospitalId, UUID roleId);

    boolean existsByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(UUID userId, UUID hospitalId, String roleCode);

    boolean existsByUserIdAndHospitalIdAndRole_NameIgnoreCaseAndActiveTrue(UUID userId, UUID hospitalId, String roleName);

    /* ------------ JPQL: any-of role codes ------------ */
    @Query("""
        SELECT (COUNT(a) > 0)
        FROM UserRoleHospitalAssignment a
        WHERE a.active = TRUE
          AND a.user.id = :userId
          AND a.hospital.id = :hospitalId
          AND UPPER(a.role.code) IN (:roleCodes)
    """)
    boolean existsActiveByUserAndHospitalAndAnyRoleCode(@Param("userId") UUID userId,
                                                        @Param("hospitalId") UUID hospitalId,
                                                        @Param("roleCodes") Set<String> roleCodes);

    /* ------------ Fetch graphs for DTO mapping ------------ */

    /** Deep enough for most response DTOs (user/hospital/role/registeredBy). */
    @EntityGraph(attributePaths = {"user", "hospital", "role", "registeredBy"})
    @NonNull
    Optional<UserRoleHospitalAssignment> findById(@NonNull UUID id);

    /** Page through assignments with their associations eagerly loaded. */
    @EntityGraph(attributePaths = {"user", "hospital", "role", "registeredBy"})
    @NonNull
    Page<UserRoleHospitalAssignment> findAll(@NonNull Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"user", "hospital", "role", "registeredBy"})
    @NonNull
    Page<UserRoleHospitalAssignment> findAll(@Nullable Specification<UserRoleHospitalAssignment> spec,
                                             @NonNull Pageable pageable);

    /** For collecting all assignments for a user when building a rich profile DTO. */
    @EntityGraph(attributePaths = {"hospital", "role"})
    Set<UserRoleHospitalAssignment> findByUser(User user);

    Optional<UserRoleHospitalAssignment> findByUserIdAndHospitalIdAndRoleId(UUID id, UUID id1, UUID id2);

    Optional<UserRoleHospitalAssignment> findFirstByUserIdAndActiveTrue(UUID id);

    Optional<UserRoleHospitalAssignment> findFirstByUserIdAndHospitalIdAndRoleId(UUID id, UUID id1, UUID roleId);

    Optional<UserRoleHospitalAssignment> findFirstByUserUsernameAndRoleCodeIgnoreCaseAndActiveTrue(String username, String roleCode);

    /* ------------ Role counts ------------ */

    /** Count DISTINCT roles across all assignments for a user (active+inactive). */
    @Query("""
    select count(distinct a.role.id)
    from UserRoleHospitalAssignment a
    where a.user.id = :userId
""")
    long countDistinctRolesByUserId(@Param("userId") UUID userId);

    /** Count DISTINCT ACTIVE roles across assignments for a user. */
    @Query("""
    select count(distinct a.role.id)
    from UserRoleHospitalAssignment a
    where a.user.id = :userId and a.active = true
""")
    long countDistinctActiveRolesByUserId(@Param("userId") UUID userId);

    /** Count DISTINCT roles for a user within a specific hospital. */
    @Query("""
    select count(distinct a.role.id)
    from UserRoleHospitalAssignment a
    where a.user.id = :userId and a.hospital.id = :hospitalId
""")
    long countDistinctRolesByUserIdAndHospitalId(@Param("userId") UUID userId,
                                                 @Param("hospitalId") UUID hospitalId);

    /** Count DISTINCT ACTIVE roles for a user within a specific hospital. */
    @Query("""
    select count(distinct a.role.id)
    from UserRoleHospitalAssignment a
    where a.user.id = :userId and a.hospital.id = :hospitalId and a.active = true
""")
    long countDistinctActiveRolesByUserIdAndHospitalId(@Param("userId") UUID userId,
                                                       @Param("hospitalId") UUID hospitalId);

    Optional<UserRoleHospitalAssignment> findByUserIdAndRoleNameAndHospitalName(UUID id, String roleName, String hospitalName);


    /* ------------ Lightweight projection for lists (optional) ------------ */

    interface AssignmentSummary {
        UUID getId();
        Boolean getActive();
        java.time.LocalDate getStartDate();
        /* role */
        @org.springframework.beans.factory.annotation.Value("#{target.role.name}")
        String getRoleName();
        @org.springframework.beans.factory.annotation.Value("#{target.role.code}")
        String getRoleCode();
        /* hospital */
        @org.springframework.beans.factory.annotation.Value("#{target.hospital != null ? target.hospital.id : null}")
        UUID getHospitalId();
        @org.springframework.beans.factory.annotation.Value("#{target.hospital != null ? target.hospital.code : null}")
        String getHospitalCode();
        @org.springframework.beans.factory.annotation.Value("#{target.hospital != null ? target.hospital.name : null}")
        String getHospitalName();
        /* user */
        @org.springframework.beans.factory.annotation.Value("#{target.user.id}")
        UUID getUserId();
        @org.springframework.beans.factory.annotation.Value("#{target.user.username}")
        String getUsername();
        @org.springframework.beans.factory.annotation.Value("#{target.user.email}")
        String getUserEmail();
    }

    @Query("""
        SELECT a FROM UserRoleHospitalAssignment a
        JOIN FETCH a.user u
        LEFT JOIN FETCH a.hospital h
        JOIN FETCH a.role r
        WHERE u.id = :userId
        ORDER BY a.createdAt DESC
    """)
    List<UserRoleHospitalAssignment> findAllDetailedByUserId(@Param("userId") UUID userId);

    @Query("""
        SELECT a FROM UserRoleHospitalAssignment a
        JOIN a.user u
        LEFT JOIN a.hospital h
        JOIN a.role r
        WHERE u.id = :userId
        ORDER BY a.createdAt DESC
    """)
    List<UserRoleHospitalAssignment> findAllShallowByUserId(@Param("userId") UUID userId);

    @Query("""
    select a
    from UserRoleHospitalAssignment a
    join a.role r
    where a.user.id = :userId
      and upper(r.code) = 'ROLE_PATIENT'
    order by a.createdAt desc
""")
    List<UserRoleHospitalAssignment> findPatientAssignmentsDesc(@Param("userId") UUID userId);

    default Optional<UserRoleHospitalAssignment> findLatestPatientAssignment(UUID userId) {
        var list = findPatientAssignmentsDesc(userId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    Optional<UserRoleHospitalAssignment> findByUser_IdAndRole_NameIgnoreCaseAndHospital_NameIgnoreCase(
        UUID userId, String roleName, String hospitalName);

    @Query(value = """
    SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END
    FROM security.user_role_hospital_assignment a
    JOIN security.roles r ON r.id = a.role_id
    WHERE a.is_active = TRUE
      AND a.user_id = :userId
      AND a.hospital_id = :hospitalId
      AND r.code ILIKE ANY(:codes)
""", nativeQuery = true)
    boolean existsActiveByUserAndHospitalAndAnyRoleCodeNative(@Param("userId") UUID userId,
                                                              @Param("hospitalId") UUID hospitalId,
                                                              @Param("codes") List<String> codes);

}

