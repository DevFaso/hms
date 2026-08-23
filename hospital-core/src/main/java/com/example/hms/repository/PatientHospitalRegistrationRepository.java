package com.example.hms.repository;

import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.PatientMultiHospitalSummaryDTO;
import com.example.hms.payload.dto.RegistrationDeskRow;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PatientHospitalRegistrationRepository extends JpaRepository<PatientHospitalRegistration, UUID> {

    Optional<PatientHospitalRegistration> findByPatientIdAndHospitalId(UUID patientId, UUID hospitalId);

    List<PatientHospitalRegistration> findByPatientId(UUID patientId);

    List<PatientHospitalRegistration> findByHospitalId(UUID hospitalId);

    /** The bulk-export runner's patient iteration (P3 #24) — paged, stable order. */
    org.springframework.data.domain.Page<PatientHospitalRegistration> findByHospitalIdAndActiveTrue(
        UUID hospitalId, org.springframework.data.domain.Pageable pageable);

    long countByHospitalIdAndActiveTrue(UUID hospitalId);

    /**
     * Desk list for one hospital, as a flat column projection.
     *
     * <p>This replaced a {@code LEFT JOIN FETCH} of the entity graph. The fetch
     * variant read correctly but could not survive the data: {@code r.patient} is
     * mapped {@code optional = false}, so a registration whose {@code patient_id}
     * points at a deleted row made Hibernate raise {@code FetchNotFoundException}
     * while assembling the result set rather than yielding null — one orphan
     * returned a bare 500 for the entire page, and because the throw happened
     * inside this call, the mapper's per-row guards never ran. The schema has no
     * foreign keys (V1 came from Hibernate SchemaExport, which emits none), so
     * orphans are a reachable state, not a hypothetical.
     *
     * <p>Projecting also drops the fetch from ~6 statements per row to one. The
     * entity path pulled {@code Patient} → {@code User} → ({@code userRoles} →
     * {@code Role}, {@code patientProfile}, {@code staffProfile}) for every row,
     * the last two forced EAGER by {@code @NotFound}.
     *
     * <p>Filtered on {@code r.hospital.id} rather than the joined {@code h.id}:
     * the former reads the FK column directly, so a registration pointing at a
     * missing hospital still lists (with null hospital fields) instead of being
     * silently filtered out of the desk.
     */
    @Query("""
           SELECT new com.example.hms.payload.dto.RegistrationDeskRow(
               r.id, r.mrn,
               p.id, u.username, p.firstName, p.lastName,
               p.email, p.phoneNumberPrimary, p.gender,
               h.id, h.name, h.code, h.address,
               r.registrationDate, r.active,
               r.stayStatus, r.stayStatusUpdatedAt,
               r.currentRoom, r.currentBed, r.attendingPhysicianName,
               r.readyForDischargeNote, r.readyByStaffId
           )
           FROM PatientHospitalRegistration r
           LEFT JOIN r.patient p
           LEFT JOIN p.user u
           LEFT JOIN r.hospital h
           WHERE r.hospital.id = :hospitalId
           """)
    List<RegistrationDeskRow> findDeskRowsByHospitalId(@Param("hospitalId") UUID hospitalId);

    Optional<PatientHospitalRegistration> findByPatientUserIdAndHospitalIdAndActiveTrue(UUID userId, UUID hospitalId);
    // 🔍 Add a method to find active registrations by patient user ID(Could not create query for public abstract )
    List<PatientHospitalRegistration> findByPatientUserIdAndActiveTrue(UUID userId);

    // Add query to find by patient username and hospital name
    @Query("SELECT r FROM PatientHospitalRegistration r WHERE r.patient.user.username = :username AND r.hospital.name = :hospitalName")
    Optional<PatientHospitalRegistration> findByPatientUsernameAndHospitalName(@Param("username") String username, @Param("hospitalName") String hospitalName);

    @Query("SELECT r FROM PatientHospitalRegistration r WHERE r.patient.user.username = :username")
    List<PatientHospitalRegistration> findByPatientUsername(@Param("username") String username);

    @Query("SELECT r FROM PatientHospitalRegistration r WHERE r.hospital.name = :hospitalName")
    List<PatientHospitalRegistration> findByHospitalName(@Param("hospitalName") String hospitalName);

    boolean existsByMrnAndHospitalId(
            @NotBlank(message = "MRN is required.") String mrn,
            @NotNull(message = "Hospital ID is required.") UUID hospitalId
    );
    boolean existsByPatientIdAndHospitalId(UUID patientId, UUID hospitalId);

    // 🔍 Add Spring Data fallback method
    Optional<PatientHospitalRegistration> findByPatientIdAndHospitalIdAndActiveTrue(UUID patientId, UUID hospitalId);

    // ✅ Fallback with proper logging
    default boolean isPatientRegisteredInHospitalFixed(UUID patientId, UUID hospitalId) {
        Optional<PatientHospitalRegistration> result = findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId);
        return result.isPresent();
    }

    @Query("SELECT r FROM PatientHospitalRegistration r WHERE r.mrn = :mrn AND r.hospital.name = :hospitalName")
    Optional<PatientHospitalRegistration> findByMrnAndHospitalName(@Param("mrn") String mrn, @Param("hospitalName") String hospitalName);

    Optional<PatientHospitalRegistration> findByMrn(String mrn);

    @Query("""
        SELECT r FROM PatientHospitalRegistration r
        JOIN FETCH r.patient p
        WHERE r.hospital.id = :hospitalId
          AND r.active = true
          AND (
                LOWER(r.mrn) = LOWER(:identifier)
             OR LOWER(COALESCE(p.email, '')) = LOWER(:identifier)
             OR LOWER(COALESCE(p.user.username, '')) = LOWER(:identifier)
             OR LOWER(COALESCE(r.patientFullName, '')) = LOWER(:identifier)
             OR LOWER(CONCAT(COALESCE(p.firstName, ''), ' ', COALESCE(p.lastName, ''))) = LOWER(:identifier)
          )
    """)
    List<PatientHospitalRegistration> findActiveByHospitalIdAndIdentifier(@Param("hospitalId") UUID hospitalId,
                                                                          @Param("identifier") String identifier);

        @Query("""
                SELECT r FROM PatientHospitalRegistration r
                JOIN FETCH r.patient p
                LEFT JOIN FETCH p.user u
                WHERE r.hospital.id = :hospitalId
                    AND r.active = true
        """)
        List<PatientHospitalRegistration> findActiveForHospitalWithPatient(@Param("hospitalId") UUID hospitalId);

    @Query("""
        SELECT new com.example.hms.payload.dto.PatientMultiHospitalSummaryDTO(
            p.id,
            CONCAT(TRIM(COALESCE(p.firstName, '')), ' ', TRIM(COALESCE(p.lastName, ''))),
            h.id,
            h.name
        )
        FROM PatientHospitalRegistration r
        JOIN r.patient p
        JOIN r.hospital h
        WHERE p.id IN (
            SELECT r2.patient.id
            FROM PatientHospitalRegistration r2
            GROUP BY r2.patient.id
            HAVING COUNT(DISTINCT r2.hospital.id) > 1
        )
        ORDER BY LOWER(COALESCE(p.lastName, '')), LOWER(COALESCE(p.firstName, '')), LOWER(h.name)
    """)
    List<PatientMultiHospitalSummaryDTO> findPatientsRegisteredInMultipleHospitals();
}
