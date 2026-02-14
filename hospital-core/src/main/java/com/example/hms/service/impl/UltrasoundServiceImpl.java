package com.example.hms.service.impl;

import com.example.hms.enums.UltrasoundOrderStatus;
import com.example.hms.enums.UltrasoundScanType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.UltrasoundMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.UltrasoundOrder;
import com.example.hms.model.UltrasoundReport;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderRequestDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderResponseDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportRequestDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UltrasoundOrderRepository;
import com.example.hms.repository.UltrasoundReportRepository;
import com.example.hms.service.UltrasoundService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class UltrasoundServiceImpl implements UltrasoundService {
    private static final String ULTRASOUND_ORDER_NOT_FOUND_PREFIX = "Ultrasound order not found with ID: ";
    private static final String ULTRASOUND_REPORT_NOT_FOUND_PREFIX = "Ultrasound report not found with ID: ";


    private final UltrasoundOrderRepository orderRepository;
    private final UltrasoundReportRepository reportRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final UltrasoundMapper ultrasoundMapper;

    @Override
    public UltrasoundOrderResponseDTO createOrder(UltrasoundOrderRequestDTO request, UUID orderedByUserId) {
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + request.getPatientId()));

        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + request.getHospitalId()));

        // Validate gestational age for scan type
        validateGestationalAgeForScanType(request.getScanType(), request.getGestationalAgeAtOrder());

        // Use mapper to create entity
        UltrasoundOrder entity = ultrasoundMapper.toOrderEntity(request, patient, hospital);
        entity.setStatus(UltrasoundOrderStatus.ORDERED);

        // Set ordered by information
        if (orderedByUserId != null) {
            Staff orderedByStaff = staffRepository.findByUserIdAndHospitalId(orderedByUserId, hospital.getId()).orElse(null);
            if (orderedByStaff != null) {
                String name = orderedByStaff.getName();
                if (name == null && orderedByStaff.getUser() != null) {
                    name = orderedByStaff.getUser().getFirstName() + " " + orderedByStaff.getUser().getLastName();
                }
                entity.setOrderedBy(name);
            }
        }

        UltrasoundOrder saved = orderRepository.save(entity);
        return ultrasoundMapper.toOrderResponseDTO(saved);
    }

    @Override
    public UltrasoundOrderResponseDTO updateOrder(UUID orderId, UltrasoundOrderRequestDTO request) {
        UltrasoundOrder order = orderRepository.findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException(ULTRASOUND_ORDER_NOT_FOUND_PREFIX + orderId));

        // Prevent modification of completed orders
        if (order.getStatus() == UltrasoundOrderStatus.COMPLETED) {
            throw new BusinessException("Cannot modify a completed ultrasound order");
        }

        if (order.getStatus() == UltrasoundOrderStatus.CANCELLED) {
            throw new BusinessException("Cannot modify a cancelled ultrasound order");
        }

        // Update hospital if changed
        if (request.getHospitalId() != null && !request.getHospitalId().equals(order.getHospital().getId())) {
            Hospital newHospital = hospitalRepository.findById(request.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + request.getHospitalId()));
            order.setHospital(newHospital);
        }

        ultrasoundMapper.updateOrderFromRequest(order, request);

        UltrasoundOrder saved = orderRepository.save(order);
        return ultrasoundMapper.toOrderResponseDTO(saved);
    }

    @Override
    public UltrasoundOrderResponseDTO cancelOrder(UUID orderId, String cancellationReason) {
        UltrasoundOrder order = orderRepository.findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException(ULTRASOUND_ORDER_NOT_FOUND_PREFIX + orderId));

        if (order.getStatus() == UltrasoundOrderStatus.CANCELLED) {
            throw new BusinessException("Order is already cancelled");
        }

        if (order.getStatus() == UltrasoundOrderStatus.COMPLETED) {
            throw new BusinessException("Cannot cancel a completed order");
        }

        order.setStatus(UltrasoundOrderStatus.CANCELLED);
        order.setCancellationReason(cancellationReason);

        UltrasoundOrder saved = orderRepository.save(order);
        return ultrasoundMapper.toOrderResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public UltrasoundOrderResponseDTO getOrderById(UUID orderId) {
        UltrasoundOrder order = orderRepository.findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException(ULTRASOUND_ORDER_NOT_FOUND_PREFIX + orderId));
        return ultrasoundMapper.toOrderResponseDTO(order);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UltrasoundOrderResponseDTO> getOrdersByPatientId(UUID patientId) {
        return orderRepository.findAllByPatientId(patientId).stream()
            .map(ultrasoundMapper::toOrderResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UltrasoundOrderResponseDTO> getOrdersByPatientIdAndStatus(UUID patientId, UltrasoundOrderStatus status) {
        return orderRepository.findByPatientIdAndStatus(patientId, status).stream()
            .map(ultrasoundMapper::toOrderResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UltrasoundOrderResponseDTO> getOrdersByHospitalId(UUID hospitalId) {
        return orderRepository.findAllByHospitalId(hospitalId).stream()
            .map(ultrasoundMapper::toOrderResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UltrasoundOrderResponseDTO> getPendingOrders(UUID hospitalId) {
        return orderRepository.findPendingOrders(hospitalId).stream()
            .map(ultrasoundMapper::toOrderResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UltrasoundOrderResponseDTO> getHighRiskOrders(UUID hospitalId) {
        return orderRepository.findAllHighRiskOrders(hospitalId).stream()
            .map(ultrasoundMapper::toOrderResponseDTO)
            .toList();
    }

    @Override
    public UltrasoundReportResponseDTO createOrUpdateReport(UUID orderId, UltrasoundReportRequestDTO request, UUID performedByUserId) {
        UltrasoundOrder order = orderRepository.findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException(ULTRASOUND_ORDER_NOT_FOUND_PREFIX + orderId));

        if (order.getStatus() == UltrasoundOrderStatus.CANCELLED) {
            throw new BusinessException("Cannot create report for a cancelled order");
        }

        String performerName = resolveStaffName(performedByUserId, order.getHospital().getId());

        UltrasoundReport report = reportRepository.findByUltrasoundOrderId(orderId).orElse(null);

        if (report == null) {
            report = ultrasoundMapper.toReportEntity(request, order, order.getHospital());
            if (performerName != null && request.getScanPerformedBy() == null) {
                report.setScanPerformedBy(performerName);
            }
        } else {
            if (Boolean.TRUE.equals(report.getReportReviewedByProvider())) {
                throw new BusinessException("Cannot modify a reviewed report. Please create an addendum or new order.");
            }
            ultrasoundMapper.updateReportFromRequest(report, request);
        }

        // Update order status
        if (order.getStatus() == UltrasoundOrderStatus.ORDERED || order.getStatus() == UltrasoundOrderStatus.SCHEDULED) {
            order.setStatus(UltrasoundOrderStatus.IN_PROGRESS);
        }

        UltrasoundReport saved = reportRepository.save(report);
        orderRepository.save(order);

        return ultrasoundMapper.toReportResponseDTO(saved);
    }

    @Override
    public UltrasoundReportResponseDTO markReportReviewed(UUID reportId, UUID reviewedByUserId) {
        UltrasoundReport report = reportRepository.findById(reportId)
            .orElseThrow(() -> new ResourceNotFoundException(ULTRASOUND_REPORT_NOT_FOUND_PREFIX + reportId));

        if (report.getReportReviewedByProvider() != null && report.getReportReviewedByProvider()) {
            throw new BusinessException("Report is already reviewed");
        }

        UUID hospitalId = report.getUltrasoundOrder().getHospital().getId();
        String reviewerName = resolveStaffName(reviewedByUserId, hospitalId);

        report.setReportReviewedByProvider(true);
        if (reviewerName != null) {
            report.setReportFinalizedBy(reviewerName);
        }
        report.setReportFinalizedAt(LocalDateTime.now());

        // Mark order as completed
        UltrasoundOrder order = report.getUltrasoundOrder();
        order.setStatus(UltrasoundOrderStatus.COMPLETED);

        UltrasoundReport saved = reportRepository.save(report);
        orderRepository.save(order);

        return ultrasoundMapper.toReportResponseDTO(saved);
    }

    @Override
    public UltrasoundReportResponseDTO markPatientNotified(UUID reportId) {
        UltrasoundReport report = reportRepository.findById(reportId)
            .orElseThrow(() -> new ResourceNotFoundException(ULTRASOUND_REPORT_NOT_FOUND_PREFIX + reportId));

        if (report.getPatientNotifiedAt() != null) {
            throw new BusinessException("Patient has already been notified");
        }

        report.setPatientNotifiedAt(LocalDateTime.now());

        UltrasoundReport saved = reportRepository.save(report);
        return ultrasoundMapper.toReportResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public UltrasoundReportResponseDTO getReportById(UUID reportId) {
        UltrasoundReport report = reportRepository.findById(reportId)
            .orElseThrow(() -> new ResourceNotFoundException(ULTRASOUND_REPORT_NOT_FOUND_PREFIX + reportId));
        return ultrasoundMapper.toReportResponseDTO(report);
    }

    @Override
    @Transactional(readOnly = true)
    public UltrasoundReportResponseDTO getReportByOrderId(UUID orderId) {
        UltrasoundReport report = reportRepository.findByUltrasoundOrderId(orderId)
            .orElseThrow(() -> new ResourceNotFoundException("Ultrasound report not found for order ID: " + orderId));
        return ultrasoundMapper.toReportResponseDTO(report);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UltrasoundReportResponseDTO> getReportsRequiringFollowUp(UUID hospitalId) {
        return reportRepository.findReportsRequiringFollowUp(hospitalId).stream()
            .map(ultrasoundMapper::toReportResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UltrasoundReportResponseDTO> getReportsWithAnomalies(UUID hospitalId) {
        return reportRepository.findReportsWithAnomalies(hospitalId).stream()
            .map(ultrasoundMapper::toReportResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public UltrasoundReportRequestDTO getNuchalTranslucencyTemplate() {
        // Return a template with typical values pre-filled for NT scan
        return UltrasoundReportRequestDTO.builder()
            .scanDate(java.time.LocalDate.now())
            .gestationalAgeAtScan(12) // weeks + days will be set by user
            .numberOfFetuses(1)
            .fetalCardiacActivity(true)
            .fetalMovementObserved(true)
            .fetalToneNormal(true)
            .findingCategory(com.example.hms.enums.UltrasoundFindingCategory.NORMAL)
            .build();
    }

    @Transactional(readOnly = true)
    @Override
    public UltrasoundReportRequestDTO getAnatomyScanTemplate() {
        // Return a template with typical values pre-filled for anatomy scan
        return UltrasoundReportRequestDTO.builder()
            .scanDate(java.time.LocalDate.now())
            .gestationalAgeAtScan(20) // weeks + days will be set by user
            .numberOfFetuses(1)
            .fetalCardiacActivity(true)
            .fetalMovementObserved(true)
            .fetalToneNormal(true)
            .anatomySurveyComplete(false)
            .findingCategory(com.example.hms.enums.UltrasoundFindingCategory.NORMAL)
            .build();
    }

    // Private helper methods

    private void validateGestationalAgeForScanType(UltrasoundScanType scanType, Integer gestationalAgeWeeks) {
        if (gestationalAgeWeeks == null) {
            return; // Optional validation
        }

        switch (scanType) {
            case NUCHAL_TRANSLUCENCY:
                if (gestationalAgeWeeks < 11 || gestationalAgeWeeks > 14) {
                    throw new BusinessException(
                        "Nuchal Translucency scan is typically performed between 11-14 weeks. Current: " + gestationalAgeWeeks + " weeks");
                }
                break;
            case ANATOMY_SCAN:
                if (gestationalAgeWeeks < 18 || gestationalAgeWeeks > 22) {
                    throw new BusinessException(
                        "Anatomy scan is typically performed between 18-22 weeks. Current: " + gestationalAgeWeeks + " weeks");
                }
                break;
            case GROWTH_SCAN:
                if (gestationalAgeWeeks < 24) {
                    throw new BusinessException(
                        "Growth scan is typically performed after 24 weeks. Current: " + gestationalAgeWeeks + " weeks");
                }
                break;
            default:
                break;
        }
    }

    private String resolveStaffName(UUID userId, UUID hospitalId) {
        if (userId == null) return null;
        Staff staff = staffRepository.findByUserIdAndHospitalId(userId, hospitalId).orElse(null);
        if (staff == null) return null;
        if (staff.getName() != null) return staff.getName();
        return staff.getUser() != null
            ? staff.getUser().getFirstName() + " " + staff.getUser().getLastName()
            : null;
    }
}
