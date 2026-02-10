package com.example.hms.repository;

import com.example.hms.model.Patient;
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
    /**
     * Extended search for patients by MRN, name, DOB, phone, email, hospital, and active status.
     */
    @Query(
        value = """
        SELECT DISTINCT p FROM Patient p
        JOIN p.hospitalRegistrations r
        WHERE (:hospitalId IS NULL OR r.hospital.id = :hospitalId)
          AND (:mrn IS NULL OR r.mrn = :mrn)
          AND (
                :namePattern IS NULL OR
                LOWER(p.firstName) LIKE :namePattern OR
                LOWER(p.lastName)  LIKE :namePattern OR
                LOWER(CONCAT(p.firstName, ' ', p.lastName)) LIKE :namePattern
              )
          AND (:dob IS NULL OR CAST(p.dateOfBirth AS string) = :dob)
          AND (:phonePattern IS NULL OR p.phoneNumberPrimary LIKE :phonePattern ESCAPE '\\' OR p.phoneNumberSecondary LIKE :phonePattern ESCAPE '\\')
          AND (:emailPattern IS NULL OR LOWER(p.email) LIKE :emailPattern)
          AND (:active IS NULL OR p.active = :active)
          AND r.active = true
          AND (
                :#{@tenantContext.isSuperAdmin()} = true OR (
                    (p.organizationId IS NOT NULL AND p.organizationId IN :#{@tenantContext.effectiveOrganizationIds()})
                    OR (p.hospitalId IS NOT NULL AND p.hospitalId IN :#{@tenantContext.effectiveHospitalIds()})
                    OR (r.hospital.id IS NOT NULL AND r.hospital.id IN :#{@tenantContext.effectiveHospitalIds()})
                )
              )
        """,
        countQuery = """
        SELECT COUNT(DISTINCT p) FROM Patient p
        JOIN p.hospitalRegistrations r
        WHERE (:hospitalId IS NULL OR r.hospital.id = :hospitalId)
          AND (:mrn IS NULL OR r.mrn = :mrn)
          AND (
                :namePattern IS NULL OR
                LOWER(p.firstName) LIKE :namePattern OR
                LOWER(p.lastName)  LIKE :namePattern OR
                LOWER(CONCAT(p.firstName, ' ', p.lastName)) LIKE :namePattern
              )
          AND (:dob IS NULL OR CAST(p.dateOfBirth AS string) = :dob)
          AND (:phonePattern IS NULL OR p.phoneNumberPrimary LIKE :phonePattern ESCAPE '\\' OR p.phoneNumberSecondary LIKE :phonePattern ESCAPE '\\')
          AND (:emailPattern IS NULL OR LOWER(p.email) LIKE :emailPattern)
          AND (:active IS NULL OR p.active = :active)
          AND r.active = true
          AND (
                :#{@tenantContext.isSuperAdmin()} = true OR (
                    (p.organizationId IS NOT NULL AND p.organizationId IN :#{@tenantContext.effectiveOrganizationIds()})
          OR (p.hospitalId IS NOT NULL AND p.hospitalId IN :#{@tenantContext.effectiveHospitalIds()})
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

    Optional<Patient> findByPhoneNumberPrimary(String phoneNumberPrimary);

    Optional<Patient> findByPhoneNumberSecondary(String phoneNumberSecondary);

    Optional<Patient> findByUserId(UUID userId);

  List<Patient> findByActive(boolean active);

    List<Patient> findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(String firstName, String lastName);

    List<Patient> findByEmailContainingIgnoreCase(String email);

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
          AND (
                :namePattern IS NULL OR
                LOWER(p.firstName) LIKE :namePattern OR
                LOWER(p.lastName)  LIKE :namePattern OR
                LOWER(CONCAT(p.firstName, ' ', p.lastName)) LIKE :namePattern
              )
          AND (:emailPattern IS NULL OR LOWER(p.email) LIKE :emailPattern)
          AND (:active IS NULL OR p.active = :active)
          AND r.active = true
          AND (
                :#{@tenantContext.isSuperAdmin()} = true OR (
                    (p.organizationId IS NOT NULL AND p.organizationId IN :#{@tenantContext.effectiveOrganizationIds()})
                    OR (p.hospitalId IS NOT NULL AND p.hospitalId IN :#{@tenantContext.effectiveHospitalIds()})
                    OR (r.hospital.id IS NOT NULL AND r.hospital.id IN :#{@tenantContext.effectiveHospitalIds()})
                )
              )
        """,
        countQuery = """
        SELECT COUNT(DISTINCT p) FROM Patient p
        JOIN p.hospitalRegistrations r
        WHERE (:hospitalId IS NULL OR r.hospital.id = :hospitalId)
          AND (
                :namePattern IS NULL OR
                LOWER(p.firstName) LIKE :namePattern OR
                LOWER(p.lastName)  LIKE :namePattern OR
                LOWER(CONCAT(p.firstName, ' ', p.lastName)) LIKE :namePattern
              )
          AND (:emailPattern IS NULL OR LOWER(p.email) LIKE :emailPattern)
          AND (:active IS NULL OR p.active = :active)
          AND r.active = true
          AND (
                :#{@tenantContext.isSuperAdmin()} = true OR (
                    (p.organizationId IS NOT NULL AND p.organizationId IN :#{@tenantContext.effectiveOrganizationIds()})
                    OR (p.hospitalId IS NOT NULL AND p.hospitalId IN :#{@tenantContext.effectiveHospitalIds()})
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
