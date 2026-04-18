package com.example.hms.repository;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AdmissionType;
import com.example.hms.model.Admission;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Admission entity
 */
@Repository
public interface AdmissionRepository extends JpaRepository<Admission, UUID> {

    /**
     * Find admissions by patient
     */
    @EntityGraph(attributePaths = {"patient", "hospital", "admittingProvider", "admittingProvider.user", "department", "attendingPhysician", "attendingPhysician.user", "dischargingProvider", "dischargingProvider.user"})
    List<Admission> findByPatientIdOrderByAdmissionDateTimeDesc(UUID patientId);

    /**
     * Find admissions by hospital
     */
    @EntityGraph(attributePaths = {"patient", "hospital", "admittingProvider", "admittingProvider.user", "department", "attendingPhysician", "attendingPhysician.user", "dischargingProvider", "dischargingProvider.user"})
    List<Admission> findByHospitalIdOrderByAdmissionDateTimeDesc(UUID hospitalId);

    /**
     * Find active admissions for a hospital
     */
    @EntityGraph(attributePaths = {"patient", "hospital", "admittingProvider", "admittingProvider.user", "department", "attendingPhysician", "attendingPhysician.user", "dischargingProvider", "dischargingProvider.user"})
    @Query("SELECT a FROM Admission a WHERE a.hospital.id = :hospitalId AND a.status IN ('ACTIVE', 'ON_LEAVE') ORDER BY a.admissionDateTime DESC")
    List<Admission> findActiveAdmissionsByHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Find admissions by admitting provider
     */
    @EntityGraph(attributePaths = {"patient", "hospital", "admittingProvider", "admittingProvider.user", "department", "attendingPhysician", "attendingPhysician.user", "dischargingProvider", "dischargingProvider.user"})
    List<Admission> findByAdmittingProviderIdOrderByAdmissionDateTimeDesc(UUID admittingProviderId);

    /**
     * Find active/awaiting-discharge admissions for a given admitting provider (doctor patient-flow board).
     * Eagerly fetches patient to avoid N+1 in buildFlowItem().
     */
    @Query("SELECT a FROM Admission a " +
           "JOIN FETCH a.patient p " +
           "WHERE a.admittingProvider.id = :staffId " +
           "AND a.status IN ('ACTIVE', 'AWAITING_DISCHARGE', 'ON_LEAVE') " +
           "ORDER BY a.admissionDateTime DESC")
    List<Admission> findActiveByAdmittingProvider(@Param("staffId") UUID staffId);

    /**
     * Find admissions by department/ward
     */
    @EntityGraph(attributePaths = {"patient", "hospital", "admittingProvider", "admittingProvider.user", "department", "attendingPhysician", "attendingPhysician.user", "dischargingProvider", "dischargingProvider.user"})
    List<Admission> findByDepartmentIdAndStatusOrderByAdmissionDateTimeDesc(UUID departmentId, AdmissionStatus status);

    /**
     * Find admissions by status
     */
    @EntityGraph(attributePaths = {"patient", "hospital", "admittingProvider", "admittingProvider.user", "department", "attendingPhysician", "attendingPhysician.user", "dischargingProvider", "dischargingProvider.user"})
    List<Admission> findByStatusOrderByAdmissionDateTimeDesc(AdmissionStatus status);

    /**
     * Find admissions by hospital and status
     */
    @EntityGraph(attributePaths = {"patient", "hospital", "admittingProvider", "admittingProvider.user", "department", "attendingPhysician", "attendingPhysician.user", "dischargingProvider", "dischargingProvider.user"})
    List<Admission> findByHospitalIdAndStatusOrderByAdmissionDateTimeDesc(UUID hospitalId, AdmissionStatus status);

    /**
     * Find admissions for the flow board — fetches both statuses in one query
     * and eagerly loads patient + hospitalRegistrations to avoid N+1.
     */
    @Query("SELECT DISTINCT a FROM Admission a " +
           "JOIN FETCH a.patient p " +
           "LEFT JOIN FETCH p.hospitalRegistrations " +
           "WHERE a.hospital.id = :hospitalId " +
           "AND a.status IN :statuses " +
           "ORDER BY a.admissionDateTime DESC")
    List<Admission> findFlowBoardAdmissions(
        @Param("hospitalId") UUID hospitalId,
        @Param("statuses") List<AdmissionStatus> statuses
    );

    /**
     * Find admissions by admission type and date range
     */
    @EntityGraph(attributePaths = {"patient", "hospital", "admittingProvider", "admittingProvider.user", "department", "attendingPhysician", "attendingPhysician.user", "dischargingProvider", "dischargingProvider.user"})
    @Query("SELECT a FROM Admission a WHERE a.hospital.id = :hospitalId AND a.admissionType = :admissionType " +
           "AND a.admissionDateTime BETWEEN :startDate AND :endDate ORDER BY a.admissionDateTime DESC")
    List<Admission> findByHospitalAndTypeAndDateRange(
        @Param("hospitalId") UUID hospitalId,
        @Param("admissionType") AdmissionType admissionType,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );

    /**
     * Get current admission for patient (if any)
     */
    @Query("SELECT a FROM Admission a WHERE a.patient.id = :patientId AND a.status IN ('PENDING', 'ACTIVE', 'ON_LEAVE') ORDER BY a.admissionDateTime DESC LIMIT 1")
    Optional<Admission> findCurrentAdmissionByPatient(@Param("patientId") UUID patientId);

    /**
     * Count active admissions by hospital
     */
    @Query("SELECT COUNT(a) FROM Admission a WHERE a.hospital.id = :hospitalId AND a.status IN ('ACTIVE', 'ON_LEAVE')")
    Long countActiveAdmissionsByHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Count admissions by hospital and date range
     */
    @Query("SELECT COUNT(a) FROM Admission a WHERE a.hospital.id = :hospitalId " +
           "AND a.admissionDateTime BETWEEN :startDate AND :endDate")
    Long countAdmissionsByHospitalAndDateRange(
        @Param("hospitalId") UUID hospitalId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );

    /**
     * Find admissions awaiting discharge
     */
    @EntityGraph(attributePaths = {"patient", "hospital", "admittingProvider", "admittingProvider.user", "department", "attendingPhysician", "attendingPhysician.user", "dischargingProvider", "dischargingProvider.user"})
    @Query("SELECT a FROM Admission a WHERE a.status = 'AWAITING_DISCHARGE' AND a.hospital.id = :hospitalId ORDER BY a.admissionDateTime ASC")
    List<Admission> findAwaitingDischarge(@Param("hospitalId") UUID hospitalId);

    /**
     * Get average length of stay by admission type
     */
    @Query("SELECT AVG(a.lengthOfStayDays) FROM Admission a WHERE a.hospital.id = :hospitalId " +
           "AND a.admissionType = :admissionType AND a.status = 'DISCHARGED' " +
           "AND a.actualDischargeDateTime BETWEEN :startDate AND :endDate")
    Double getAverageLengthOfStay(
        @Param("hospitalId") UUID hospitalId,
        @Param("admissionType") AdmissionType admissionType,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );

    @Override
    @EntityGraph(attributePaths = {"patient", "hospital", "admittingProvider", "admittingProvider.user", "department", "attendingPhysician", "attendingPhysician.user", "dischargingProvider", "dischargingProvider.user"})
    List<Admission> findAll();

    /**
     * Find admissions with room/bed assignment
     */
    @EntityGraph(attributePaths = {"patient", "hospital", "admittingProvider", "admittingProvider.user", "department", "attendingPhysician", "attendingPhysician.user", "dischargingProvider", "dischargingProvider.user"})
    List<Admission> findByHospitalIdAndRoomBedContainingIgnoreCaseOrderByRoomBed(UUID hospitalId, String roomBedSearch);
}
