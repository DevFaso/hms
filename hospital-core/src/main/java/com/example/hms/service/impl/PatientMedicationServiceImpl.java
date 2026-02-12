package com.example.hms.service.impl;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.payload.dto.medication.PatientMedicationResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.service.PatientMedicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PatientMedicationServiceImpl implements PatientMedicationService {

    private static final Pattern DAYS_PATTERN = Pattern.compile("(\\d++)\\s*+(day|days)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKS_PATTERN = Pattern.compile("(\\d++)\\s*+(week|weeks)", Pattern.CASE_INSENSITIVE);
    private static final int DEFAULT_LIMIT = 50;

    private final PrescriptionRepository prescriptionRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;

    @Override
    @Transactional(readOnly = true)
    public List<PatientMedicationResponseDTO> getMedicationsForPatient(UUID patientId, UUID hospitalId, int limit) {
        log.info("Fetching medications for patient {} in hospital {}", patientId, hospitalId);

        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + patientId));

        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + hospitalId));

        List<Prescription> prescriptions = prescriptionRepository.findByPatient_IdAndHospital_Id(patient.getId(), hospital.getId());
        int limitToApply = limit > 0 ? limit : DEFAULT_LIMIT;

        Comparator<Prescription> byCreatedAt = Comparator.comparing(
            Prescription::getCreatedAt,
            Comparator.nullsLast(Comparator.naturalOrder())
        );

        return prescriptions.stream()
            .sorted(byCreatedAt.reversed())
            .limit(limitToApply)
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    private PatientMedicationResponseDTO toResponse(Prescription prescription) {
        LocalDate startDate = Optional.ofNullable(prescription.getCreatedAt())
            .map(LocalDateTime::toLocalDate)
            .orElse(null);
        LocalDate endDate = resolveEndDate(startDate, prescription.getDuration());

        return PatientMedicationResponseDTO.builder()
            .id(prescription.getId())
            .medicationName(resolveMedicationName(prescription))
            .dosage(prescription.getDosage())
            .frequency(prescription.getFrequency())
            .route(prescription.getRoute())
            .status(resolveStatus(prescription.getStatus(), endDate))
            .startDate(startDate)
            .endDate(endDate)
            .prescribedBy(Optional.ofNullable(prescription.getStaff()).map(staff -> staff.getFullName()).orElse(null))
            .indication(prescription.getNotes())
            .instructions(prescription.getInstructions())
            .build();
    }

    private String resolveMedicationName(Prescription prescription) {
        String displayName = prescription.getMedicationDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return prescription.getMedicationName();
    }

    private LocalDate resolveEndDate(LocalDate startDate, String duration) {
        if (startDate == null || duration == null || duration.isBlank()) {
            return null;
        }

        Matcher daysMatcher = DAYS_PATTERN.matcher(duration);
        if (daysMatcher.find()) {
            int days = parseInt(daysMatcher.group(1));
            if (days > 0) {
                return startDate.plusDays(days);
            }
        }

        Matcher weeksMatcher = WEEKS_PATTERN.matcher(duration);
        if (weeksMatcher.find()) {
            int weeks = parseInt(weeksMatcher.group(1));
            if (weeks > 0) {
                return startDate.plusWeeks(weeks);
            }
        }

        return null;
    }

    private int parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private String resolveStatus(PrescriptionStatus status, LocalDate endDate) {
        if (status == null) {
            return endDate != null && endDate.isBefore(LocalDate.now()) ? "COMPLETED" : "ACTIVE";
        }

        return switch (status) {
            case DISCONTINUED, CANCELLED -> "DISCONTINUED";
            case DRAFT, PENDING_SIGNATURE, TRANSMISSION_FAILED -> "ON_HOLD";
            default -> endDate != null && endDate.isBefore(LocalDate.now()) ? "COMPLETED" : "ACTIVE";
        };
    }
}
