package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabResultReferenceRangeDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.lab.PatientLabResultResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.PatientLabResultService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PatientLabResultServiceImpl implements PatientLabResultService {

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private static final String STATUS_NORMAL = "NORMAL";
    private static final String STATUS_ABNORMAL_HIGH = "ABNORMAL_HIGH";
    private static final String STATUS_ABNORMAL_LOW = "ABNORMAL_LOW";
    private static final String STATUS_CRITICAL = "CRITICAL";
    private static final String STATUS_PENDING = "PENDING";

    private final LabResultRepository labResultRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final LabResultMapper labResultMapper;

    @Override
    @Transactional(readOnly = true)
    public List<PatientLabResultResponseDTO> getLabResultsForPatient(UUID patientId, UUID hospitalId, int limit) {
        log.info("Fetching lab results for patient {} in hospital {}", patientId, hospitalId);

        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + patientId));

        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + hospitalId));

        int effectiveLimit = limit > 0 ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT;
        Pageable pageable = PageRequest.of(0, effectiveLimit, Sort.by(Sort.Direction.DESC, "resultDate"));

        List<LabResult> results = labResultRepository
            .findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(patient.getId(), hospital.getId(), pageable);

        return results.stream()
            .map(this::toResponse)
            .toList();
    }

    private PatientLabResultResponseDTO toResponse(LabResult result) {
        LabOrder labOrder = result.getLabOrder();
        LabTestDefinition testDefinition = labOrder != null ? labOrder.getLabTestDefinition() : null;
        LabResultResponseDTO mapped = labResultMapper.toResponseDTO(result);

        String unit = resolveUnit(result, testDefinition);
        String referenceRange = formatReferenceRange(mapped != null ? mapped.getReferenceRanges() : null, unit);
        String status = resolveStatus(result, mapped);

        return PatientLabResultResponseDTO.builder()
            .id(result.getId())
            .testName(resolveTestName(labOrder, mapped))
            .testCode(testDefinition != null ? testDefinition.getTestCode() : null)
            .value(result.getResultValue())
            .unit(unit)
            .referenceRange(referenceRange)
            .status(status)
            .collectedAt(labOrder != null ? labOrder.getOrderDatetime() : null)
            .resultedAt(result.getResultDate())
            .orderedBy(resolveStaffName(labOrder != null ? labOrder.getOrderingStaff() : null))
            .performedBy(resolveAssignmentUser(result.getAssignment()))
            .category(testDefinition != null ? testDefinition.getCategory() : null)
            .notes(result.getNotes())
            .build();
    }

    private String resolveUnit(LabResult result, LabTestDefinition definition) {
        if (result.getResultUnit() != null && !result.getResultUnit().isBlank()) {
            return result.getResultUnit();
        }
        if (definition != null && definition.getUnit() != null && !definition.getUnit().isBlank()) {
            return definition.getUnit();
        }
        return null;
    }

    private String resolveTestName(LabOrder order, LabResultResponseDTO mapped) {
        if (order != null && order.getLabTestDefinition() != null
            && order.getLabTestDefinition().getName() != null
            && !order.getLabTestDefinition().getName().isBlank()) {
            return order.getLabTestDefinition().getName();
        }
        if (order != null && order.getClinicalIndication() != null && !order.getClinicalIndication().isBlank()) {
            return order.getClinicalIndication();
        }
        if (mapped != null && mapped.getLabTestName() != null && !mapped.getLabTestName().isBlank()) {
            return mapped.getLabTestName();
        }
        return "Lab Result";
    }

    private String resolveStatus(LabResult result, LabResultResponseDTO mapped) {
        if (!result.isReleased()) {
            return STATUS_PENDING;
        }
        String severity = mapped != null ? mapped.getSeverityFlag() : null;
        if (severity == null || severity.isBlank()) {
            return STATUS_NORMAL;
        }
        switch (severity.toUpperCase(Locale.ROOT)) {
            case STATUS_CRITICAL:
                return STATUS_CRITICAL;
            case "HIGH":
                return result.isAcknowledged() ? STATUS_ABNORMAL_HIGH : STATUS_CRITICAL;
            case "LOW":
                return STATUS_ABNORMAL_LOW;
            case STATUS_NORMAL:
                return STATUS_NORMAL;
            default:
                return STATUS_NORMAL;
        }
    }

    private String resolveStaffName(Staff staff) {
        if (staff == null) {
            return null;
        }
        String fullName = staff.getFullName();
        if (fullName != null && !fullName.isBlank()) {
            return fullName;
        }
        if (staff.getName() != null && !staff.getName().isBlank()) {
            return staff.getName();
        }
        return Optional.ofNullable(staff.getUser())
            .map(this::resolveUserDisplay)
            .orElse(null);
    }

    private String resolveAssignmentUser(UserRoleHospitalAssignment assignment) {
        if (assignment == null) {
            return null;
        }
        return resolveUserDisplay(assignment.getUser());
    }

    private String resolveUserDisplay(User user) {
        if (user == null) {
            return null;
        }
        String firstName = user.getFirstName() != null ? user.getFirstName().trim() : "";
        String lastName = user.getLastName() != null ? user.getLastName().trim() : "";
        String full = (firstName + " " + lastName).trim();
        if (!full.isBlank()) {
            return full;
        }
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            return user.getEmail();
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        return null;
    }

    private String formatReferenceRange(List<LabResultReferenceRangeDTO> ranges, String fallbackUnit) {
        if (ranges == null || ranges.isEmpty()) {
            return null;
        }
        LabResultReferenceRangeDTO range = ranges.get(0);
        Double min = range.getMinValue();
        Double max = range.getMaxValue();
        String unit = range.getUnit();
        if (unit == null || unit.isBlank()) {
            unit = fallbackUnit;
        }
        String unitSuffix = unit != null && !unit.isBlank() ? " " + unit : "";

        if (min != null && max != null) {
            return formatNumber(min) + " - " + formatNumber(max) + unitSuffix;
        }
        if (min != null) {
            return ">= " + formatNumber(min) + unitSuffix;
        }
        if (max != null) {
            return "<= " + formatNumber(max) + unitSuffix;
        }
        return null;
    }

    private String formatNumber(Double value) {
        if (value == null) {
            return null;
        }
        return new java.text.DecimalFormat("0.##").format(value);
    }
}
