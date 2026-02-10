package com.example.hms.repository;

import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.PatientMultiHospitalSummaryDTO;
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

    // ✅ Temporary fallback with logging for debugging purposes
    @SuppressWarnings({"java:S106"})
    default boolean isPatientRegisteredInHospitalFixed(UUID patientId, UUID hospitalId) {
        Optional<PatientHospitalRegistration> result = findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId);
        if (result.isPresent()) {
            System.out.println("[DEBUG] Found active registration for patientId=" + patientId + ", hospitalId=" + hospitalId);
            return true;
        } else {
            System.out.println("[DEBUG] No active registration for patientId=" + patientId + ", hospitalId=" + hospitalId);
            return false;
        }
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
