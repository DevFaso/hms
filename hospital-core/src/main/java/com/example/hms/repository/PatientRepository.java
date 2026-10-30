package com.example.hms.repository;

import com.example.hms.model.Patient;
import com.example.hms.utility.PatientMatchKeys;
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
public interface PatientRepository extends JpaRepository<Patient, UUID> {

    // -- QA D924/D942/D943: the stored side of every name and phone comparison --
    // Folded exactly like PatientMatchKeys folds the typed side: names
    // lower-cased with accents removed, phone numbers reduced to their digits.
    // PatientMatchKeysGuardTest fails the build when a query here compares a
    // name or phone column without them.

    /** Lower-cases {@code column} and removes its accents. */
    String FOLD_OPEN = "TRANSLATE(LOWER(";
    /** Closes {@link #FOLD_OPEN}. */
    String FOLD_CLOSE = "), '" + PatientMatchKeys.NAME_FOLD_FROM + "', '" + PatientMatchKeys.NAME_FOLD_TO + "')";

    /**
     * {@code :namePattern} (built by {@link PatientMatchKeys#nameContainsPattern})
     * against the first name, the last name, "first last", "last first" and the
     * MRN, accents ignored on both sides.
     */
    String NAME_OR_MRN_MATCH = "(:namePattern IS NULL"
        + " OR " + FOLD_OPEN + "p.firstName" + FOLD_CLOSE + " LIKE :namePattern"
        + " OR " + FOLD_OPEN + "p.lastName" + FOLD_CLOSE + " LIKE :namePattern"
        + " OR " + FOLD_OPEN + "CONCAT(p.firstName, ' ', p.lastName)" + FOLD_CLOSE + " LIKE :namePattern"
        + " OR " + FOLD_OPEN + "CONCAT(p.lastName, ' ', p.firstName)" + FOLD_CLOSE + " LIKE :namePattern"
        + " OR LOWER(r.mrn) LIKE :namePattern)";

    /** {@code p.phoneNumberPrimary} reduced to its digits ("+226 70-00.10/02" becomes "22670001002"). */
    String PRIMARY_PHONE_DIGITS =
        PatientMatchKeys.DIGITS_SQL_OPEN + "p.phoneNumberPrimary" + PatientMatchKeys.DIGITS_SQL_CLOSE;
    /** {@code p.phoneNumberSecondary} reduced to its digits. */
    String SECONDARY_PHONE_DIGITS =
        PatientMatchKeys.DIGITS_SQL_OPEN + "p.phoneNumberSecondary" + PatientMatchKeys.DIGITS_SQL_CLOSE;

    /**
     * {@code :phonePattern} (built by {@link PatientMatchKeys#phoneContainsPattern})
     * against the digits of either number, however it was stored.
     */
    String PHONE_MATCH = "(:phonePattern IS NULL"
        + " OR " + PRIMARY_PHONE_DIGITS + " LIKE :phonePattern"
        + " OR " + SECONDARY_PHONE_DIGITS + " LIKE :phonePattern)";

    /**
     * Extended search for patients by MRN, name, DOB, phone, email, hospital, and active status.
     */
    @Query(
        value = """
        SELECT DISTINCT p FROM Patient p
        JOIN p.hospitalRegistrations r
        WHERE (:hospitalId IS NULL OR r.hospital.id = :hospitalId)
          AND (:mrn IS NULL OR r.mrn = :mrn)
          AND """ + NAME_OR_MRN_MATCH + """
          AND (:dob IS NULL OR CAST(p.dateOfBirth AS string) = :dob)
          AND """ + PHONE_MATCH + """
          AND (:emailPattern IS NULL OR LOWER(p.email) LIKE :emailPattern)
          AND (:active IS NULL OR p.active = :active)
          AND r.active = true
          AND (
                :#{@tenantContext.isGlobalView()} = true OR (
                    (p.hospitalId IS NOT NULL AND p.hospitalId IN :#{@tenantContext.effectiveHospitalIds()})
                    OR (r.hospital.id IS NOT NULL AND r.hospital.id IN :#{@tenantContext.effectiveHospitalIds()})
                )
              )
        """,
        countQuery = """
        SELECT COUNT(DISTINCT p) FROM Patient p
        JOIN p.hospitalRegistrations r
        WHERE (:hospitalId IS NULL OR r.hospital.id = :hospitalId)
          AND (:mrn IS NULL OR r.mrn = :mrn)
          AND """ + NAME_OR_MRN_MATCH + """
          AND (:dob IS NULL OR CAST(p.dateOfBirth AS string) = :dob)
          AND """ + PHONE_MATCH + """
          AND (:emailPattern IS NULL OR LOWER(p.email) LIKE :emailPattern)
          AND (:active IS NULL OR p.active = :active)
          AND r.active = true
          AND (
                :#{@tenantContext.isGlobalView()} = true OR (
          (p.hospitalId IS NOT NULL AND p.hospitalId IN :#{@tenantContext.effectiveHospitalIds()})
          OR (r.hospital.id IS NOT NULL AND r.hospital.id IN :#{@tenantContext.effectiveHospitalIds()})
                )
              )
        """
    )
  @SuppressWarnings("java:S107")
  Page<Patient> searchPatientsExtended(
    @Param("mrn") String mrn,
    @Param("namePattern") String namePattern,
    @Param("dob") String dob,
  @Param("phonePattern") String phonePattern,
    @Param("emailPattern") String emailPattern,
        @Param("hospitalId") UUID hospitalId,
        @Param("active") Boolean active,
        Pageable pageable
    );
  // Lookup by Medical Record Number (MRN)
  @Query("SELECT p FROM Patient p JOIN p.hospitalRegistrations r WHERE r.mrn = :mrn")
  List<Patient> findByMrn(@Param("mrn") String mrn);

    // QA D926/D942: a number may be shared (a child carries the mother's
    // phone, and the database has no unique constraint), so no lookup by
    // number is single-result: these return the first holder, never throw.
    Optional<Patient> findFirstByPhoneNumberPrimary(String phoneNumberPrimary);

    Optional<Patient> findFirstByPhoneNumberSecondary(String phoneNumberSecondary);

    /** Every holder of a number stored exactly so (a short or legacy value; review round 10). */
    List<Patient> findAllByPhoneNumberPrimary(String phoneNumberPrimary);

    List<Patient> findAllByPhoneNumberSecondary(String phoneNumberSecondary);

    /** Whether a patient other than {@code id} holds this primary number. */
    boolean existsByPhoneNumberPrimaryAndIdNot(String phoneNumberPrimary, UUID id);

    /** Another patient already holds this e-mail, in any letter case (QA D113). */
    @Query("SELECT COUNT(p) > 0 FROM Patient p WHERE LOWER(p.email) = LOWER(:email) AND p.id <> :id")
    boolean existsEmailOnOtherPatient(@Param("email") String email, @Param("id") UUID id);

    Optional<Patient> findByUserId(UUID userId);

    /**
     * QA D1064: the patients born on {@code dateOfBirth} registered at any
     * hospital of {@code organizationId} - the candidates a registration's
     * same-name-and-birth-date match is decided among (names compared in Java,
     * accent-folded, by PatientMatchKeys.sameIdentity).
     */
    @Query("SELECT DISTINCT p FROM Patient p JOIN p.hospitalRegistrations r "
        + "WHERE p.dateOfBirth = :birthDate AND r.hospital.organization.id = :organizationId")
    List<Patient> findByDateOfBirthInOrganization(@Param("birthDate") java.time.LocalDate dateOfBirth,
                                                  @Param("organizationId") UUID organizationId);

    /**
     * Is this patient row one of the ones linked to this user account?
     *
     * <p>The question every "is this record the caller’s" check actually asks,
     * answered without loading the row. Two reasons it is shaped this way
     * rather than as {@link #findByUserId}:
     *
     * <ul>
     *   <li>{@code findByUserId} is a single-result query, and
     *       {@code V113__patients_user_id_integrity.sql} deliberately falls
     *       back to a plain index instead of failing the deploy when a tenant
     *       already carries duplicate {@code user_id} rows. On such a tenant
     *       the single-result form throws
     *       {@code IncorrectResultSizeDataAccessException} — a 500 where an
     *       authorisation check owes a decision. This form cannot, however
     *       many rows there are.</li>
     *   <li>Materialising a {@code Patient} decrypts every PHI column through
     *       {@code EncryptedStringConverter}. An authorisation check has no
     *       business reading a name to compare two UUIDs.</li>
     * </ul>
     */
    boolean existsByIdAndUserId(UUID id, UUID userId);

    /**
     * Does any patient row point at this user account? Same shape, and same
     * reasons, as {@link #existsByIdAndUserId}: an authorisation check must not
     * throw on duplicate rows nor decrypt a name to answer yes or no.
     */
    @Query("SELECT (COUNT(p) > 0) FROM Patient p WHERE p.user.id = :userId")
    boolean existsByUserId(@Param("userId") UUID userId);

    /**
     * QA D819: is this account already the login of a patient record other
     * than {@code patientId}? Same shape as {@link #existsByUserId}.
     */
    boolean existsByUser_IdAndIdNot(UUID userId, UUID patientId);

    /**
     * Fetches a Patient by primary key WITHOUT tenant-scope filtering.
     * <p>
     * Since E9 #57 the tenant-scoped {@code findById} (via
     * {@code TenantAwareJpaRepository}) scopes a patient by their
     * registrations, so a patient registered at a second hospital is found
     * there. Before that it keyed on {@code Patient.hospitalId}, the FIRST
     * hospital, and missed them — the reason this bypass exists.
     * <p>
     * It stays for callers that resolve the patient first and authorise
     * separately (a registration check, {@code PatientChartAccess}, a
     * super-admin global view). Callers <b>must</b> enforce access control
     * themselves when they use it.
     */
    @Query("SELECT p FROM Patient p WHERE p.id = :id")
    Optional<Patient> findByIdUnscoped(@Param("id") UUID id);

    /**
     * QA D624 (user decision): would the caller's patient search find this
     * patient? The same tenant clause as {@link #searchPatientsExtended}: an
     * active registration, global view for an unpinned super-admin, otherwise
     * a registration (or home hospital) among the caller's readable hospitals.
     * Break-the-glass answers an id outside this scope exactly like an unknown
     * one, so it reveals nothing the search does not already show.
     */
    @Query("""
        SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END
        FROM PatientHospitalRegistration r JOIN r.patient p
        WHERE p.id = :patientId
          AND r.active = true
          AND (
                :#{@tenantContext.isGlobalView()} = true OR (
                  :#{@tenantContext.hasHospitalScope()} = true AND (
                    (p.hospitalId IS NOT NULL AND p.hospitalId IN :#{@tenantContext.effectiveHospitalIdsOrNone()})
                    OR (r.hospital.id IS NOT NULL AND r.hospital.id IN :#{@tenantContext.effectiveHospitalIdsOrNone()})
                  )
                )
              )
        """)
    boolean isFindableByPatientSearch(@Param("patientId") UUID patientId);

    /**
     * QA D304: row lock on the patient, so two concurrent admissions of the
     * same patient serialise and the second sees the first's open stay.
     * Same access rule as {@link #findByIdUnscoped(UUID)}.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Patient p WHERE p.id = :id")
    Optional<Patient> lockByIdUnscoped(@Param("id") UUID id);

  List<Patient> findByActive(boolean active);

    /**
     * QA D943: first name OR last name contains the typed text, accents and
     * case ignored on both sides (the EMPI matcher's name block and the audit
     * resource-name lookup). An empty text matches every name, as the derived
     * query it replaces did; a '%' or '_' in the text is a literal character,
     * escaped as the derived query escaped it (review round 14).
     */
    @Query("SELECT p FROM Patient p WHERE "
        + FOLD_OPEN + "p.firstName" + FOLD_CLOSE + " LIKE CONCAT('%', "
        + FOLD_OPEN + PatientMatchKeys.LIKE_ESCAPE_SQL_OPEN + "CAST(:firstName AS string)" + PatientMatchKeys.LIKE_ESCAPE_SQL_CLOSE
        + FOLD_CLOSE + ", '%')"
        + " OR " + FOLD_OPEN + "p.lastName" + FOLD_CLOSE + " LIKE CONCAT('%', "
        + FOLD_OPEN + PatientMatchKeys.LIKE_ESCAPE_SQL_OPEN + "CAST(:lastName AS string)" + PatientMatchKeys.LIKE_ESCAPE_SQL_CLOSE
        + FOLD_CLOSE + ", '%')")
    List<Patient> findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(
        @Param("firstName") String firstName, @Param("lastName") String lastName);

    List<Patient> findByEmailContainingIgnoreCase(String email);

    // ── Registration-time exact matchers (cross-hospital by design) ──────────
    // Derived finders bypass tenant scoping: a receptionist at hospital B must be
    // able to discover that the person standing at the desk already exists at
    // hospital A, so the record can be LINKED instead of duplicated. Callers must
    // only expose minimal, masked fields (see RegistrationMatchDTO).
    List<Patient> findAllByEmailIgnoreCase(String email);

    /**
     * QA D942: every patient whose primary or secondary number ends with
     * {@code subscriberPattern} ({@code "%" + the eight national digits}),
     * however the number was stored ("70001002", "+22670001002",
     * "70 00 10 02"). Cross-hospital by design, like the matchers above.
     */
    @Query("SELECT p FROM Patient p WHERE " + PRIMARY_PHONE_DIGITS + " LIKE :subscriberPattern"
        + " OR " + SECONDARY_PHONE_DIGITS + " LIKE :subscriberPattern ORDER BY p.createdAt DESC, p.id")
    List<Patient> findAllByPhoneSubscriber(@Param("subscriberPattern") String subscriberPattern,
                                           Pageable pageable);

    /**
     * {@link #findAllByPhoneSubscriber} at one hospital (an active
     * registration there): the duplicate check's candidates, so a number
     * shared across the country cannot crowd this hospital's patient out of
     * the capped page.
     */
    @Query("SELECT DISTINCT p FROM Patient p JOIN p.hospitalRegistrations r"
        + " WHERE r.hospital.id = :hospitalId AND r.active = true"
        + " AND (" + PRIMARY_PHONE_DIGITS + " LIKE :subscriberPattern"
        + " OR " + SECONDARY_PHONE_DIGITS + " LIKE :subscriberPattern) ORDER BY p.createdAt DESC, p.id")
    List<Patient> findAllByPhoneSubscriberAtHospital(@Param("subscriberPattern") String subscriberPattern,
                                                     @Param("hospitalId") UUID hospitalId,
                                                     Pageable pageable);

    /** The candidate cap of {@link #findAllByPhoneSubscriber}: a shared number never loads a crowd. */
    int PHONE_SUBSCRIBER_CAP = 50;

    /**
     * Retrieves all patients registered to a specific hospital with active registrations.
     */
    @Query("""
        SELECT p FROM Patient p
        JOIN p.hospitalRegistrations r
        WHERE r.hospital.id = :hospitalId AND r.active = true
    """)
    List<Patient> findByHospitalId(@Param("hospitalId") UUID hospitalId);

    @Query(
        value = """
        SELECT DISTINCT p FROM Patient p
        JOIN p.hospitalRegistrations r
        WHERE r.hospital.id = :hospitalId
          AND r.active = true
          AND (:active IS NULL OR p.active = :active)
        """,
        countQuery = """
        SELECT COUNT(DISTINCT p) FROM Patient p
        JOIN p.hospitalRegistrations r
        WHERE r.hospital.id = :hospitalId
          AND r.active = true
          AND (:active IS NULL OR p.active = :active)
        """
    )
    Page<Patient> findPageByHospitalIdAndActive(
        @Param("hospitalId") UUID hospitalId,
        @Param("active") Boolean active,
        Pageable pageable
    );

    /**
     * Retrieves the MRN number for a specific patient in a hospital (if active registration exists).
     */
    @Query("""
        SELECT r.mrn FROM PatientHospitalRegistration r
        WHERE r.patient.id = :patientId AND r.hospital.id = :hospitalId AND r.active = true
    """)
  Optional<String> findMrnForHospital(@Param("patientId") UUID patientId, @Param("hospitalId") UUID hospitalId);


    @Query(
        value = """
        SELECT DISTINCT p FROM Patient p
        JOIN p.hospitalRegistrations r
        WHERE (:hospitalId IS NULL OR r.hospital.id = :hospitalId)
          AND """ + NAME_OR_MRN_MATCH + """
          AND (:emailPattern IS NULL OR LOWER(p.email) LIKE :emailPattern)
          AND (:active IS NULL OR p.active = :active)
          AND r.active = true
          AND (
                :#{@tenantContext.isGlobalView()} = true OR (
                    (p.hospitalId IS NOT NULL AND p.hospitalId IN :#{@tenantContext.effectiveHospitalIds()})
                    OR (r.hospital.id IS NOT NULL AND r.hospital.id IN :#{@tenantContext.effectiveHospitalIds()})
                )
              )
        """,
        countQuery = """
        SELECT COUNT(DISTINCT p) FROM Patient p
        JOIN p.hospitalRegistrations r
        WHERE (:hospitalId IS NULL OR r.hospital.id = :hospitalId)
          AND """ + NAME_OR_MRN_MATCH + """
          AND (:emailPattern IS NULL OR LOWER(p.email) LIKE :emailPattern)
          AND (:active IS NULL OR p.active = :active)
          AND r.active = true
          AND (
                :#{@tenantContext.isGlobalView()} = true OR (
                    (p.hospitalId IS NOT NULL AND p.hospitalId IN :#{@tenantContext.effectiveHospitalIds()})
                    OR (r.hospital.id IS NOT NULL AND r.hospital.id IN :#{@tenantContext.effectiveHospitalIds()})
                )
              )
        """
    )
  Page<Patient> searchPatients(@Param("namePattern") String namePattern,
                 @Param("emailPattern") String emailPattern,
                                 @Param("hospitalId") UUID hospitalId,
                                 @Param("active") Boolean active,
                                 Pageable pageable);

    Optional<Patient> findByUserUsername(String username);

    // Legacy alias if something still calls findByUsername
  /**
   * @deprecated Prefer {@link #findByUserUsername(String)} to align with naming conventions.
   */
  @Deprecated(forRemoval = false, since = "2025.09")
  @SuppressWarnings("java:S1133")
    @Query("SELECT p FROM Patient p WHERE p.user.username = :username")
    Optional<Patient> findByUsername(String username);

    /**
     * Find patient by username or email (case-insensitive search)
     */
    @Query("""
        SELECT p FROM Patient p
        WHERE LOWER(p.user.username) = LOWER(:identifier)
        OR LOWER(p.email) = LOWER(:identifier)
    """)
    Optional<Patient> findByUsernameOrEmail(@Param("identifier") String identifier);
}
