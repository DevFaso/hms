package com.example.hms.repository;

import com.example.hms.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    /**
     * Does ANOTHER account (deleted ones included) hold this username, ignoring
     * case? Login resolves usernames case-insensitively while uq_user_username
     * does not, so a case variant would make both accounts unresolvable.
     */
    @Query("select (count(u) > 0) from User u where lower(u.username) = lower(:username) and u.id <> :id")
    boolean existsUsernameOnOtherAccount(@Param("username") String username, @Param("id") UUID id);

    /** The same question for an email. */
    @Query("select (count(u) > 0) from User u where lower(u.email) = lower(:email) and u.id <> :id")
    boolean existsEmailOnOtherAccount(@Param("email") String email, @Param("id") UUID id);

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
    /*
     * The directory (GET /users, /users/search), one WHERE clause shared by
     * each value query and its count, so a filter is fixed once and a page's
     * total can never count rows its content would not show.
     *
     * Scope (UserAccountAccess.requireDirectoryAccess): with :scoped = false
     * (the super-admin) every account, the deleted view included on request.
     * With :scoped = true only LIVE accounts holding an assignment, any role,
     * active or not, at one of :hospitalIds; EXISTS, not a join, so an
     * account assigned at two of those hospitals is one row. The scope is in
     * the query: filtering a global page in memory would load other tenants'
     * rows and break the paging. An unscoped call passes DIRECTORY_UNSCOPED,
     * a sentinel no hospital has, because an empty IN list is not portable.
     */
    String DIRECTORY_VISIBLE = """
        WHERE ((:onlyDeleted = true AND u.isDeleted = true)
           OR (:onlyDeleted = false AND (:includeDeleted = true OR u.isDeleted = false)))
          AND (:scoped = false
               OR (u.isDeleted = false
                   AND EXISTS (
                       SELECT 1 FROM UserRoleHospitalAssignment ha
                       WHERE ha.user = u
                         AND ha.hospital.id IN :hospitalIds)))
        """;

    /*
     * The search filters. When scoped, the role must be held through an
     * ACTIVE assignment at one of the caller's hospitals, and a global
     * UserRole does not count: otherwise a nurse at A searching
     * role=HOSPITAL_ADMIN would find A's receptionist because that account
     * administers hospital B, which is another tenant's fact.
     */
    String DIRECTORY_SEARCH_FILTERS = """
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
                      AND (:scoped = false OR a.hospital.id IN :hospitalIds)
                      AND (LOWER(r.code) = LOWER(cast(:role AS string)) OR LOWER(r.name) = LOWER(cast(:role AS string)))
                )
                OR (:scoped = false
                    AND EXISTS (
                        SELECT 1 FROM UserRole ur
                        JOIN ur.role r2
                        WHERE ur.id.userId = u.id
                          AND (LOWER(r2.code) = LOWER(cast(:role AS string)) OR LOWER(r2.name) = LOWER(cast(:role AS string)))
                    ))
              )
        """;

    /** The hospital set an unscoped directory call passes: a sentinel no hospital has. */
    java.util.Set<UUID> DIRECTORY_UNSCOPED = java.util.Set.of(new UUID(0L, 0L));

    @Query(value = "SELECT u FROM User u " + DIRECTORY_VISIBLE + DIRECTORY_SEARCH_FILTERS,
        countQuery = "SELECT COUNT(u) FROM User u " + DIRECTORY_VISIBLE + DIRECTORY_SEARCH_FILTERS)
    Page<User> searchUsers(@Param("name") String name,
                           @Param("role") String role,
                           @Param("email") String email,
                           @Param("includeDeleted") boolean includeDeleted,
                           @Param("onlyDeleted") boolean onlyDeleted,
                           @Param("scoped") boolean scoped,
                           @Param("hospitalIds") Collection<UUID> hospitalIds,
                           Pageable pageable);

    /**
     * The admin list. {@code includeDeleted=true} surfaces soft-deleted rows
     * — the portal has had a Deleted filter and a Restore button since the
     * users screen shipped, and both were unreachable because this query
     * always filtered them out: a deleted user still holds their unique
     * email (uq_user_email has no isDeleted carve-out), so "already
     * registered" pointed at a row nobody could see or restore. Scoped as
     * {@link #DIRECTORY_VISIBLE} says; a scoped caller never sees the
     * deleted view.
     */
    @Query(value = "SELECT u FROM User u " + DIRECTORY_VISIBLE,
        countQuery = "SELECT COUNT(u) FROM User u " + DIRECTORY_VISIBLE)
    Page<User> findAllPaged(@Param("includeDeleted") boolean includeDeleted,
                            @Param("onlyDeleted") boolean onlyDeleted,
                            @Param("scoped") boolean scoped,
                            @Param("hospitalIds") Collection<UUID> hospitalIds,
                            Pageable pageable);

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
