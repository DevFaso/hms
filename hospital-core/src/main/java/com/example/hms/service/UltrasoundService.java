package com.example.hms.service;

import com.example.hms.enums.UltrasoundOrderStatus;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderRequestDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderResponseDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportRequestDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportResponseDTO;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for ultrasound order and report management.
 */
public interface UltrasoundService {

    /**
     * Get all reports requiring follow-up.
     */
    List<UltrasoundReportResponseDTO> getReportsRequiringFollowUp(UUID hospitalId);

    /**
     * Mark a report as reviewed by provider.
     */
    UltrasoundReportResponseDTO markReportReviewed(UUID reportId, UUID reviewedByUserId);

    /**
     * Mark patient as notified about report.
     */
    UltrasoundReportResponseDTO markPatientNotified(UUID reportId);

    UltrasoundOrderResponseDTO createOrder(UltrasoundOrderRequestDTO request, UUID orderedByUserId);

    UltrasoundOrderResponseDTO updateOrder(UUID orderId, UltrasoundOrderRequestDTO request);

    UltrasoundOrderResponseDTO cancelOrder(UUID orderId, String cancellationReason);

    // Additional methods used by controller
    UltrasoundOrderResponseDTO getOrderById(UUID orderId);

    List<UltrasoundOrderResponseDTO> getOrdersByPatientId(UUID patientId);

    List<UltrasoundOrderResponseDTO> getOrdersByPatientIdAndStatus(UUID patientId, UltrasoundOrderStatus status);

    List<UltrasoundOrderResponseDTO> getOrdersByHospitalId(UUID hospitalId);

    List<UltrasoundOrderResponseDTO> getPendingOrders(UUID hospitalId);

    List<UltrasoundOrderResponseDTO> getHighRiskOrders(UUID hospitalId);

    UltrasoundReportResponseDTO createOrUpdateReport(UUID orderId, UltrasoundReportRequestDTO request, UUID performedByUserId);

    UltrasoundReportResponseDTO getReportById(UUID reportId);

    UltrasoundReportResponseDTO getReportByOrderId(UUID orderId);

    List<UltrasoundReportResponseDTO> getReportsWithAnomalies(UUID hospitalId);
}
