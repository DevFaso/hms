package com.example.hms.repository;

import com.example.hms.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

  /* ---------- Lightweight counts for dashboards ---------- */
  long countByIsActiveTrueAndIsDeletedFalse();

    /* ---------- Existence checks (case-insensitive where it matters) ---------- */
    @Query("select (count(u) > 0) from User u where lower(u.username) = lower(:username)")
    Boolean existsByUsername(@Param("username") String username);

    @Query("select (count(u) > 0) from User u where lower(u.email) = lower(:email)")
    Boolean existsByEmail(@Param("email") String email);

    /* ---------- Simple finders ---------- */
    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmail(@Param("email") String email);

    Optional<User> findByPhoneNumber(String phoneNumber);

    @Query("select max(u.phoneNumber) from User u where u.phoneNumber like concat(:prefix, '%')")
    Optional<String> findMaxPhoneNumberWithPrefix(@Param("prefix") String prefix);

    @Query("select u.username from User u where lower(u.username) like lower(concat(:prefix, '%'))")
    List<String> findUsernamesByPrefix(@Param("prefix") String prefix);

  @Query("select u from User u where lower(u.email) in :emails")
  List<User> findByEmailInIgnoreCase(@Param("emails") List<String> emails);

  @Query("select u from User u where lower(u.username) in :usernames")
  List<User> findByUsernameInIgnoreCase(@Param("usernames") List<String> usernames);

    /* Case-insensitive username finders with roles pre-fetched (for auth) */
    @EntityGraph(attributePaths = {"userRoles", "userRoles.role"})
    @Query("select u from User u where lower(u.username) = lower(:username)")
    Optional<User> findByUsername(@Param("username") String username);

    @EntityGraph(attributePaths = {"userRoles", "userRoles.role"})
    Optional<User> findByUsernameIgnoreCase(String username);

    /* Login identifier: email (ci) or phone (exact) */
    @Query("""
        select u from User u
        where u.isDeleted = false
          and (lower(u.email) = lower(:identifier) or u.phoneNumber = :identifier)
    """)
    Optional<User> findActiveByEmailOrPhone(@Param("identifier") String identifier);

    /* ---------- Rich fetch for mapping a single user (roles + profiles) ---------- */
    // NOTE: avoid deep nested paths like "staffProfile.assignment.role" — in Hibernate 6
    // the @Query + @EntityGraph combination returns Optional.empty() when the intermediate
    // association (staffProfile) is null, even though the user row exists.
    @EntityGraph(attributePaths = {
        "userRoles", "userRoles.role",
        "staffProfile",
        "patientProfile"
    })
    @Query("select u from User u where u.id = :id and u.isDeleted = false")
    Optional<User> findByIdWithRolesAndProfiles(@Param("id") UUID id);

    /* Keep this for places you already use it */
    @EntityGraph(attributePaths = { "userRoles", "userRoles.role", "staffProfile" })
    Optional<User> findWithRolesById(UUID id);

    /* ---------- Search & paging ---------- */

    /**
     * Lightweight search; uses EXISTS for role filter so we don't load roles.
     * Map with a second step (findByIdWithRolesAndProfiles) if you need roles per item.
     */
    @Query("""
        SELECT u FROM User u
        WHERE u.isDeleted = false
          AND ( :name IS NULL
                OR LOWER(COALESCE(u.firstName, '')) LIKE LOWER(CONCAT('%', :name, '%'))
                OR LOWER(COALESCE(u.lastName,  '')) LIKE LOWER(CONCAT('%', :name, '%'))
                OR LOWER(u.username)               LIKE LOWER(CONCAT('%', :name, '%'))
              )
          AND ( :email IS NULL
                OR LOWER(u.email) LIKE LOWER(CONCAT('%', :email, '%'))
              )
          AND ( :role IS NULL
                OR EXISTS (
                    SELECT 1 FROM UserRoleHospitalAssignment a
                    JOIN a.role r
                    WHERE a.user = u
                      AND a.active = true
                      AND (LOWER(r.code) = LOWER(:role) OR LOWER(r.name) = LOWER(:role))
                )
              )
        """)
    Page<User> searchUsers(@Param("name") String name,
                           @Param("role") String role,
                           @Param("email") String email,
                           Pageable pageable);

    /**
     * Paged list of non-deleted users.
     * NOTE: @EntityGraph is intentionally omitted here — combining a collection-fetch
     * entity graph with a LIMIT/OFFSET paged query triggers Hibernate 6 warning
     * HHH90003004 ("firstResult/maxResults specified with collection fetch") and
     * causes incorrect pagination (full table loaded in memory, then sliced).
     * UserMapper.toSummaryDTO accesses userRoles lazily which is fine inside the
     * surrounding @Transactional(readOnly = true) boundary.
     */
    @Query("select u from User u where u.isDeleted = false")
    Page<User> findAllActive(Pageable pageable);

  List<User> findByIsDeletedFalse();

    /* ---------- Data-quality helpers ---------- */

    /**
     * Users who were given 'patient' role but no Patient row exists.
     * Good for repair scripts.
     */
    @Query("""
        SELECT DISTINCT u FROM User u
        WHERE u.isDeleted = false
          AND EXISTS (
              SELECT 1 FROM UserRoleHospitalAssignment a
              JOIN a.role r
              WHERE a.user = u
                AND a.active = true
                AND (LOWER(r.code) = 'role_patient' OR LOWER(r.name) = 'patient')
          )
          AND NOT EXISTS (
              SELECT 1 FROM Patient p WHERE p.user = u
          )
        """)
    List<User> findUsersWithRolePatientButNoPatientEntry();

    Optional<User> findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(
        String username, String email, String phoneNumber
    );

}
