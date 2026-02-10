package com.example.hms.repository;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AdmissionType;
import com.example.hms.model.Admission;
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
    List<Admission> findByPatientIdOrderByAdmissionDateTimeDesc(UUID patientId);

    /**
     * Find admissions by hospital
     */
    List<Admission> findByHospitalIdOrderByAdmissionDateTimeDesc(UUID hospitalId);

    /**
     * Find active admissions for a hospital
     */
    @Query("SELECT a FROM Admission a WHERE a.hospital.id = :hospitalId AND a.status IN ('ACTIVE', 'ON_LEAVE') ORDER BY a.admissionDateTime DESC")
    List<Admission> findActiveAdmissionsByHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Find admissions by admitting provider
     */
    List<Admission> findByAdmittingProviderIdOrderByAdmissionDateTimeDesc(UUID admittingProviderId);

    /**
     * Find admissions by department/ward
     */
    List<Admission> findByDepartmentIdAndStatusOrderByAdmissionDateTimeDesc(UUID departmentId, AdmissionStatus status);

    /**
     * Find admissions by status
     */
    List<Admission> findByStatusOrderByAdmissionDateTimeDesc(AdmissionStatus status);

    /**
     * Find admissions by hospital and status
     */
    List<Admission> findByHospitalIdAndStatusOrderByAdmissionDateTimeDesc(UUID hospitalId, AdmissionStatus status);

    /**
     * Find admissions by admission type and date range
     */
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

    /**
     * Find admissions with room/bed assignment
     */
    List<Admission> findByHospitalIdAndRoomBedContainingIgnoreCaseOrderByRoomBed(UUID hospitalId, String roomBedSearch);
}
