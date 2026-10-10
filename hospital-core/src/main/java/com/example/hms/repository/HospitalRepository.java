package com.example.hms.repository;

import com.example.hms.enums.FacilityType;
import com.example.hms.enums.HospitalLifecycleState;
import com.example.hms.model.Hospital;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HospitalRepository extends JpaRepository<Hospital, UUID> {

    /**
     * The clinical predicate (provider plan AC-11): a hospital, never a
     * pharmacy or laboratory. Concatenated into every clinical query, with the
     * fully-qualified enum literal JPQL needs; padded with a space on each
     * side, so it splices between two text blocks as safely as into a string.
     */
    String CLINICAL_ONLY = " h.facilityType = com.example.hms.enums.FacilityType.HOSPITAL ";

    @Query("SELECT h FROM Hospital h WHERE LOWER(h.name) = LOWER(:identifier) OR LOWER(h.code) = LOWER(:identifier) OR LOWER(h.email) = LOWER(:identifier)")
    java.util.Optional<Hospital> findByNameOrCodeOrEmail(@Param("identifier") String identifier);

    /** All hospital IDs whose lifecycle state matches one of the given states (MVP-c batch). */
    @Query("SELECT h.id FROM Hospital h WHERE h.lifecycleState IN :states")
    List<UUID> findIdsByLifecycleStateIn(@Param("states") Collection<HospitalLifecycleState> states);

    boolean existsByNameIgnoreCaseAndZipCode(String name, String zipCode);

    /**
     * Hospital search backing the super-admin scope-chip typeahead and the
     * legacy "search hospitals" admin pages. The {@code name} clause uses
     * <b>prefix</b> match ({@code LOWER(name) LIKE 'memo%'}) so the
     * functional B-tree index from
     * {@code V90__hospital_name_search_index.sql} (on {@code LOWER(name)})
     * is load-bearing rather than decorative — substring match would
     * force a sequential scan on every keystroke at 10k+ tenants. See
     * design call #3 / F2 in {@code docs/super-admin-cross-tenant-design.md}.
     *
     * <p>{@code city} / {@code state} stay on substring match: the
     * typeahead doesn't surface those fields, the legacy admin search
     * exercises them rarely, and there's no equivalent index to make
     * load-bearing.</p>
     *
     * <p><b>Postgres NULL-bind workaround.</b> All String parameters are
     * wrapped in {@code CAST(:p AS string)} so Postgres can type-infer the
     * placeholder when the caller passes {@code null}. Without the cast,
     * {@code lower('%' || ? || '%')} with {@code ?} bound as {@code SQL NULL}
     * resolves to {@code bytea} on Postgres and fails parse-time with
     * {@code ERROR: function lower(bytea) does not exist} — the same UAT
     * 500 that hit {@code LabTestDefinitionRepository.search}. H2 is more
     * lenient. Same pattern as {@code UserRepository#searchByCriteria} and
     * {@code findAllWithDepartments} below.</p>
     *
     * <p>Clinical hospitals only (provider plan AC-11): a pharmacy or
     * laboratory is not a scope a hospital page picks.</p>
     */
    @Query("SELECT h FROM Hospital h LEFT JOIN FETCH h.organization WHERE " +
            "(:name IS NULL OR LOWER(h.name) LIKE LOWER(CONCAT(CAST(:name AS string), '%'))) AND " +
            "(:city IS NULL OR LOWER(h.city) LIKE LOWER(CONCAT('%', CAST(:city AS string), '%'))) AND " +
            "(:state IS NULL OR LOWER(h.state) LIKE LOWER(CONCAT('%', CAST(:state AS string), '%'))) AND " +
            "(:active IS NULL OR h.active = :active) AND" +
            CLINICAL_ONLY +
            "ORDER BY LOWER(h.name)")
    Slice<Hospital> searchHospitals(@Param("name") String name,
                                   @Param("city") String city,
                                   @Param("state") String state,
                                   @Param("active") Boolean active,
                                   Pageable pageable);

    Optional<Hospital> findByCodeIgnoreCase(String code);

    Optional<Hospital> findByNameIgnoreCase(String hospitalName);

    Optional<Hospital> findByName(String name);

    /*
     * Lists, counts and KPIs are CLINICAL by default (provider plan AC-11): a
     * pharmacy or laboratory row is never a "hospital" there. A new list or
     * count that must also return providers says so in its name
     * (...AnyFacilityType, ...ByFacilityType). A clinical destination named
     * by id reads findClinicalById (below); the plain by-id reads see every
     * type and each caller is recorded with its reason. A clinical destination
     * named by name or code reads a findClinicalBy... finder (below); the plain
     * name and code lookups see every type. HospitalRepositoryCallerCoverageTest holds
     * every caller of an unfiltered list, count, lookup or by-id read to a
     * recorded reason.
     */

    /** Every clinical hospital: the super-admin's global scope, platform KPIs. */
    @Query("SELECT h FROM Hospital h WHERE " + CLINICAL_ONLY)
    List<Hospital> findAllHospitals();

    /** Dashboard count: clinical hospitals. */
    @Query("SELECT COUNT(h) FROM Hospital h WHERE " + CLINICAL_ONLY)
    long countHospitals();

    /** Dashboard count: active clinical hospitals. */
    @Query("SELECT COUNT(h) FROM Hospital h WHERE h.active = true AND " + CLINICAL_ONLY)
    long countActiveHospitals();

    /** B1: the laboratories a clinician may route an order to — every active hospital, by name. */
    List<Hospital> findByActiveTrueAndLifecycleStateOrderByNameAsc(com.example.hms.enums.HospitalLifecycleState lifecycleState);

    /**
     * The facility row, locked for a state change (provider onboarding and
     * the lifecycle restore). Every provider transition takes this lock
     * first, so a verify, a reject, a revoke, a resubmit and a restore of one
     * provider are serialised and each re-reads the state the last one left.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM Hospital h WHERE h.id = :id")
    Optional<Hospital> findByIdForUpdate(@Param("id") UUID id);

    /**
     * The requireClinicalHospital rule (provider plan AC-11, item 4): a
     * hospital by id, for any request-supplied CLINICAL destination (a
     * booking, an admission, an order, a referral, a registration...). A
     * pharmacy or laboratory row is not found, so the caller gets exactly the
     * answer an unknown id gets at that site. A plain findById of a hospital
     * is a recorded exception in HospitalRepositoryCallerCoverageTest.
     */
    @Query("SELECT h FROM Hospital h WHERE h.id = :id"
        + " AND " + CLINICAL_ONLY)
    Optional<Hospital> findClinicalById(@Param("id") UUID id);

    /** A clinical destination named by its exact name, any case (requireClinicalHospital, AC-11). */
    @Query("SELECT h FROM Hospital h WHERE LOWER(h.name) = LOWER(:name)"
        + " AND " + CLINICAL_ONLY)
    Optional<Hospital> findClinicalByNameIgnoreCase(@Param("name") String name);

    /** A clinical destination named by its exact name (requireClinicalHospital, AC-11). */
    @Query("SELECT h FROM Hospital h WHERE h.name = :name"
        + " AND " + CLINICAL_ONLY)
    Optional<Hospital> findClinicalByName(@Param("name") String name);

    /** A clinical destination named by its code, any case (requireClinicalHospital, AC-11). */
    @Query("SELECT h FROM Hospital h WHERE LOWER(h.code) = LOWER(:code)"
        + " AND " + CLINICAL_ONLY)
    Optional<Hospital> findClinicalByCodeIgnoreCase(@Param("code") String code);

    /** A clinical destination named by its name, code or email, any case (requireClinicalHospital, AC-11). */
    @Query("SELECT h FROM Hospital h WHERE (LOWER(h.name) = LOWER(:identifier) OR LOWER(h.code) = LOWER(:identifier)"
        + " OR LOWER(h.email) = LOWER(:identifier)) AND " + CLINICAL_ONLY)
    Optional<Hospital> findClinicalByNameOrCodeOrEmail(@Param("identifier") String identifier);

    /* Organization-related queries */
    /**
     * Clinical hospitals with no organisation: the input of the two boot jobs
     * that attach such rows to an organisation and seed its policies. A
     * provider facility (PHARMACY, LABORATORY) is never returned, so it is
     * never attached to a hospital organisation (provider plan AC-11).
     */
    @Query("SELECT h FROM Hospital h WHERE h.organization IS NULL"
        + " AND " + CLINICAL_ONLY)
    List<Hospital> findByOrganizationIsNull();

    /** An organisation's clinical hospitals, by name. */
    @Query("SELECT h FROM Hospital h WHERE h.organization.id = :organizationId AND " + CLINICAL_ONLY + " ORDER BY h.name ASC")
    List<Hospital> findByOrganizationIdOrderByNameAsc(@Param("organizationId") UUID organizationId);

    @Query("""
      SELECT DISTINCT h FROM Hospital h
      LEFT JOIN FETCH h.departments d
      LEFT JOIN FETCH d.headOfDepartment hod
      LEFT JOIN FETCH hod.user u
      WHERE (:activeOnly IS NULL OR h.active = :activeOnly)
    AND""" + CLINICAL_ONLY + """
    AND (
      :hospitalQuery IS NULL OR :hospitalQuery = '' OR
      LOWER(CAST(h.name AS string)) LIKE LOWER(CONCAT('%', CAST(:hospitalQuery AS string), '%')) OR
      LOWER(CAST(h.code AS string)) LIKE LOWER(CONCAT('%', CAST(:hospitalQuery AS string), '%')) OR
      LOWER(COALESCE(CAST(h.city AS string), '')) LIKE LOWER(CONCAT('%', CAST(:hospitalQuery AS string), '%'))
    )
    """)
    List<Hospital> findAllWithDepartments(@Param("hospitalQuery") String hospitalQuery,
                            @Param("activeOnly") Boolean activeOnly);

    @Query("""
      SELECT h FROM Hospital h
      WHERE (:organizationId IS NULL OR h.organization.id = :organizationId)
        AND""" + CLINICAL_ONLY + """
        AND (:unassignedOnly IS NULL OR :unassignedOnly = false OR h.organization IS NULL)
        AND (
            :city IS NULL
            OR LOWER(COALESCE(CAST(h.city AS string), '')) LIKE LOWER(CONCAT('%', CAST(:city AS string), '%'))
        )
        AND (
            :state IS NULL
            OR LOWER(COALESCE(CAST(h.state AS string), '')) LIKE LOWER(CONCAT('%', CAST(:state AS string), '%'))
        )
      ORDER BY LOWER(CAST(h.name AS string))
    """)
    List<Hospital> findAllForFilters(@Param("organizationId") UUID organizationId,
                                     @Param("unassignedOnly") Boolean unassignedOnly,
                                     @Param("city") String city,
                                     @Param("state") String state);

    /**
     * {@link #findAllForFilters} for one facility type the caller names: the
     * super-admin's explicit {@code facilityType} filter on the hospital list
     * (provider plan AC-11, "super-admin views take an explicit facilityType
     * filter"). NOT clinical-only: it returns PHARMACY or LABORATORY rows when
     * asked. Its only caller refuses anyone but a verified super-admin first.
     */
    @Query("""
      SELECT h FROM Hospital h
      WHERE h.facilityType = :facilityType
        AND (:organizationId IS NULL OR h.organization.id = :organizationId)
        AND (:unassignedOnly IS NULL OR :unassignedOnly = false OR h.organization IS NULL)
        AND (
            :city IS NULL
            OR LOWER(COALESCE(CAST(h.city AS string), '')) LIKE LOWER(CONCAT('%', CAST(:city AS string), '%'))
        )
        AND (
            :state IS NULL
            OR LOWER(COALESCE(CAST(h.state AS string), '')) LIKE LOWER(CONCAT('%', CAST(:state AS string), '%'))
        )
      ORDER BY LOWER(CAST(h.name AS string))
    """)
    List<Hospital> findAllForFiltersByFacilityType(@Param("facilityType") FacilityType facilityType,
                                                   @Param("organizationId") UUID organizationId,
                                                   @Param("unassignedOnly") Boolean unassignedOnly,
                                                   @Param("city") String city,
                                                   @Param("state") String state);
}

