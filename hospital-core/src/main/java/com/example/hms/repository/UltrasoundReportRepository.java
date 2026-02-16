package com.example.hms.repository;

import com.example.hms.enums.UltrasoundFindingCategory;
import com.example.hms.model.UltrasoundReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for UltrasoundReport entity.
 */
@Repository
public interface UltrasoundReportRepository extends JpaRepository<UltrasoundReport, UUID> {

    /**
     * Find report by ultrasound order ID.
     */
    @Query("SELECT r FROM UltrasoundReport r WHERE r.ultrasoundOrder.id = :orderId")
    Optional<UltrasoundReport> findByUltrasoundOrderId(@Param("orderId") UUID orderId);

    /**
     * Find all reports for a patient (through orders).
     */
    @Query("SELECT r FROM UltrasoundReport r WHERE r.ultrasoundOrder.patient.id = :patientId ORDER BY r.scanDate DESC")
    List<UltrasoundReport> findAllByPatientId(@Param("patientId") UUID patientId);

    /**
     * Find reports by finding category.
     */
    @Query("SELECT r FROM UltrasoundReport r WHERE r.findingCategory = :category ORDER BY r.scanDate DESC")
    List<UltrasoundReport> findByFindingCategory(@Param("category") UltrasoundFindingCategory category);

    /**
     * Find reports with abnormal findings.
     */
    @Query("SELECT r FROM UltrasoundReport r WHERE r.anomaliesDetected = true ORDER BY r.scanDate DESC")
    List<UltrasoundReport> findReportsWithAnomalies();

    /**
     * Find reports requiring follow-up.
     */
    @Query("SELECT r FROM UltrasoundReport r WHERE r.followUpRequired = true ORDER BY r.scanDate DESC")
    List<UltrasoundReport> findReportsRequiringFollowUp();

    /**
     * Find reports requiring specialist referral.
     */
    @Query("SELECT r FROM UltrasoundReport r WHERE r.specialistReferralNeeded = true AND r.hospital.id = :hospitalId ORDER BY r.scanDate DESC")
    List<UltrasoundReport> findReportsRequiringReferralByHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Find finalized reports within date range.
     */
    @Query("SELECT r FROM UltrasoundReport r WHERE r.reportFinalizedAt BETWEEN :startDate AND :endDate ORDER BY r.reportFinalizedAt DESC")
    List<UltrasoundReport> findFinalizedReportsBetween(@Param("startDate") java.time.LocalDateTime startDate, @Param("endDate") java.time.LocalDateTime endDate);

    /**
     * Find reports not yet reviewed by provider.
     */
    @Query("SELECT r FROM UltrasoundReport r WHERE r.reportReviewedByProvider = false AND r.hospital.id = :hospitalId ORDER BY r.scanDate DESC")
    List<UltrasoundReport> findUnreviewedReportsByHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Find reports by hospital within date range.
     */
    @Query("SELECT r FROM UltrasoundReport r WHERE r.hospital.id = :hospitalId AND r.scanDate BETWEEN :startDate AND :endDate ORDER BY r.scanDate DESC")
    List<UltrasoundReport> findByHospitalIdAndScanDateBetween(@Param("hospitalId") UUID hospitalId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /**
     * Find reports requiring follow-up for a specific hospital.
     */
    @Query("SELECT r FROM UltrasoundReport r WHERE r.followUpRequired = true AND r.hospital.id = :hospitalId ORDER BY r.scanDate DESC")
    List<UltrasoundReport> findReportsRequiringFollowUp(@Param("hospitalId") UUID hospitalId);

    /**
     * Find reports with abnormal findings for a specific hospital.
     */
    @Query("SELECT r FROM UltrasoundReport r WHERE r.anomaliesDetected = true AND r.hospital.id = :hospitalId ORDER BY r.scanDate DESC")
    List<UltrasoundReport> findReportsWithAnomalies(@Param("hospitalId") UUID hospitalId);
}
