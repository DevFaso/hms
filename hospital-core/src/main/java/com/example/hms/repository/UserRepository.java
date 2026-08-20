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
  long countByIsDeletedFalse();
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
  
    @EntityGraph(attributePaths = {
        "userRoles", "userRoles.role",
        "staffProfile",
        "patientProfile"
    })
    @Query("select u from User u where u.id = :id and u.isDeleted = false")
    Optional<User> findByIdWithRolesAndProfiles(@Param("id") UUID id);

    /* ---------- OIDC / Keycloak identity link ---------- */
    Optional<User> findByKeycloakSubject(String keycloakSubject);

    /* Keep this for places you already use it */
    @EntityGraph(attributePaths = { "userRoles", "userRoles.role", "staffProfile" })
    Optional<User> findWithRolesById(UUID id);

    /* ---------- Search & paging ---------- */


    /*
     * NOTE on the cast(... as string) wrappers:
     * PostgreSQL cannot infer the JDBC type of an unknown bind parameter
     * surrounded only by untyped string literals (e.g. ('%' || ? || '%')).
     * In that situation the planner fell back to bytea and failed the call
     * with `function lower(bytea) does not exist` (Position 824 in the
     * generated SQL). The explicit cast(:param as string) anchors each
     * parameter to text so PostgreSQL keeps the LOWER overload on text and
     * the LIKE pattern is built correctly. This affects PostgreSQL only —
     * H2 (used by tests) handles untyped binds without complaint, so this
     * 500 surfaces only in the dev/UAT/prod profiles.
     */
    @Query(value = """
        SELECT u FROM User u
        WHERE u.isDeleted = false
          AND ( :name IS NULL
                OR LOWER(COALESCE(u.firstName, '')) LIKE LOWER(CONCAT('%', cast(:name AS string), '%'))
                OR LOWER(COALESCE(u.lastName,  '')) LIKE LOWER(CONCAT('%', cast(:name AS string), '%'))
                OR LOWER(u.username)               LIKE LOWER(CONCAT('%', cast(:name AS string), '%'))
              )
          AND ( :email IS NULL
                OR LOWER(u.email) LIKE LOWER(CONCAT('%', cast(:email AS string), '%'))
              )
          AND ( :role IS NULL
                OR EXISTS (
                    SELECT 1 FROM UserRoleHospitalAssignment a
                    JOIN a.role r
                    WHERE a.user = u
                      AND a.active = true
                      AND (LOWER(r.code) = LOWER(cast(:role AS string)) OR LOWER(r.name) = LOWER(cast(:role AS string)))
                )
                OR EXISTS (
                    SELECT 1 FROM UserRole ur
                    JOIN ur.role r2
                    WHERE ur.id.userId = u.id
                      AND (LOWER(r2.code) = LOWER(cast(:role AS string)) OR LOWER(r2.name) = LOWER(cast(:role AS string)))
                )
              )
        """,
        countQuery = """
        SELECT COUNT(u) FROM User u
        WHERE u.isDeleted = false
          AND ( :name IS NULL
                OR LOWER(COALESCE(u.firstName, '')) LIKE LOWER(CONCAT('%', cast(:name AS string), '%'))
                OR LOWER(COALESCE(u.lastName,  '')) LIKE LOWER(CONCAT('%', cast(:name AS string), '%'))
                OR LOWER(u.username)               LIKE LOWER(CONCAT('%', cast(:name AS string), '%'))
              )
          AND ( :email IS NULL
                OR LOWER(u.email) LIKE LOWER(CONCAT('%', cast(:email AS string), '%'))
              )
          AND ( :role IS NULL
                OR EXISTS (
                    SELECT 1 FROM UserRoleHospitalAssignment a
                    JOIN a.role r
                    WHERE a.user = u
                      AND a.active = true
                      AND (LOWER(r.code) = LOWER(cast(:role AS string)) OR LOWER(r.name) = LOWER(cast(:role AS string)))
                )
                OR EXISTS (
                    SELECT 1 FROM UserRole ur
                    JOIN ur.role r2
                    WHERE ur.id.userId = u.id
                      AND (LOWER(r2.code) = LOWER(cast(:role AS string)) OR LOWER(r2.name) = LOWER(cast(:role AS string)))
                )
              )
        """)
    Page<User> searchUsers(@Param("name") String name,
                           @Param("role") String role,
                           @Param("email") String email,
                           Pageable pageable);


    @Query("select u from User u where u.isDeleted = false")
    Page<User> findAllPaged(Pageable pageable);

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

    /**
     * Null-safe identity lookup for phone-first (email-less) registrations.
     * NEVER pass a null email to the 3-way variant above — Spring Data rewrites
     * a null argument into an {@code email IS NULL} predicate, which matches
     * EVERY email-less user in the system.
     */
    Optional<User> findFirstByUsernameIgnoreCaseOrPhoneNumber(String username, String phoneNumber);

    @Query("select (count(u) > 0) from User u where u.phoneNumber = :phone")
    Boolean existsByPhoneNumber(@Param("phone") String phone);

}
