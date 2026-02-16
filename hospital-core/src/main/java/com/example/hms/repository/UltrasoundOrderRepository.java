package com.example.hms.repository;

import com.example.hms.enums.UltrasoundOrderStatus;
import com.example.hms.enums.UltrasoundScanType;
import com.example.hms.model.UltrasoundOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for UltrasoundOrder entity.
 */
@Repository
public interface UltrasoundOrderRepository extends JpaRepository<UltrasoundOrder, UUID> {

    /**
     * Find all ultrasound orders for a specific patient.
     */
    @Query("SELECT u FROM UltrasoundOrder u WHERE u.patient.id = :patientId ORDER BY u.orderedDate DESC")
    List<UltrasoundOrder> findAllByPatientId(@Param("patientId") UUID patientId);

    /**
     * Find ultrasound orders by status.
     */
    List<UltrasoundOrder> findByStatusOrderByOrderedDateDesc(UltrasoundOrderStatus status);

    /**
     * Find ultrasound orders for a patient by status.
     */
    @Query("SELECT u FROM UltrasoundOrder u WHERE u.patient.id = :patientId AND u.status = :status ORDER BY u.orderedDate DESC")
    List<UltrasoundOrder> findByPatientIdAndStatus(@Param("patientId") UUID patientId, @Param("status") UltrasoundOrderStatus status);

    /**
     * Find ultrasound orders by scan type for a patient.
     */
    @Query("SELECT u FROM UltrasoundOrder u WHERE u.patient.id = :patientId AND u.scanType = :scanType ORDER BY u.orderedDate DESC")
    List<UltrasoundOrder> findByPatientIdAndScanType(@Param("patientId") UUID patientId, @Param("scanType") UltrasoundScanType scanType);

    /**
     * Find all ultrasound orders for high-risk pregnancies.
     */
    @Query("SELECT u FROM UltrasoundOrder u WHERE u.isHighRiskPregnancy = true ORDER BY u.orderedDate DESC")
    List<UltrasoundOrder> findAllHighRiskOrders();

    /**
     * Find orders scheduled for a specific date range.
     */
    @Query("SELECT u FROM UltrasoundOrder u WHERE u.scheduledDate BETWEEN :startDate AND :endDate ORDER BY u.scheduledDate, u.scheduledTime")
    List<UltrasoundOrder> findOrdersScheduledBetween(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /**
     * Find orders by hospital.
     */
    @Query("SELECT u FROM UltrasoundOrder u WHERE u.hospital.id = :hospitalId ORDER BY u.orderedDate DESC")
    List<UltrasoundOrder> findByHospitalId(@Param("hospitalId") UUID hospitalId);

    /**
     * Count total scans for a patient (for tracking scan frequency).
     */
    @Query("SELECT COUNT(u) FROM UltrasoundOrder u WHERE u.patient.id = :patientId AND u.status <> 'CANCELLED'")
    long countByPatientId(@Param("patientId") UUID patientId);

    /**
     * Find the most recent order for a patient.
     */
    Optional<UltrasoundOrder> findFirstByPatient_IdOrderByOrderedDateDesc(UUID patientId);

    /**
     * Find orders with reports available (status = REPORT_AVAILABLE).
     */
    @Query("SELECT u FROM UltrasoundOrder u WHERE u.status = 'REPORT_AVAILABLE' AND u.hospital.id = :hospitalId ORDER BY u.orderedDate DESC")
    List<UltrasoundOrder> findOrdersWithReportsAvailableByHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Find all ultrasound orders for a specific hospital.
     */
    @Query("SELECT u FROM UltrasoundOrder u WHERE u.hospital.id = :hospitalId ORDER BY u.orderedDate DESC")
    List<UltrasoundOrder> findAllByHospitalId(@Param("hospitalId") UUID hospitalId);

    /**
     * Find pending ultrasound orders for a specific hospital.
     */
    @Query("SELECT u FROM UltrasoundOrder u WHERE u.hospital.id = :hospitalId AND u.status = 'ORDERED' ORDER BY u.orderedDate ASC")
    List<UltrasoundOrder> findPendingOrders(@Param("hospitalId") UUID hospitalId);

    /**
     * Find all high-risk pregnancy ultrasound orders for a specific hospital.
     */
    @Query("SELECT u FROM UltrasoundOrder u WHERE u.hospital.id = :hospitalId AND u.isHighRiskPregnancy = true ORDER BY u.orderedDate DESC")
    List<UltrasoundOrder> findAllHighRiskOrders(@Param("hospitalId") UUID hospitalId);
}
