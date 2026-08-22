package com.example.hms.service.impl;

import com.example.hms.enums.IntakeOutputCategory;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.IntakeOutputEntry;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.IntakeOutputEntryRequestDTO;
import com.example.hms.payload.dto.IntakeOutputSummaryDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.IntakeOutputEntryRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.IntakeOutputService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class IntakeOutputServiceImpl implements IntakeOutputService {

    private static final String MSG_PATIENT_NOT_FOUND = "Patient not found with ID: ";
    private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final IntakeOutputEntryRepository entryRepository;

    @Override
    public IntakeOutputSummaryDTO.Entry record(UUID patientId,
                                               UUID hospitalId,
                                               UUID recorderUserId,
                                               IntakeOutputEntryRequestDTO request) {
        if (hospitalId == null) {
            throw new BusinessException("An active hospital is required to record intake/output.");
        }
        if (request == null || request.getRoute() == null) {
            throw new BusinessException("An intake/output route is required.");
        }
        if (request.getVolumeMl() == null || request.getVolumeMl() <= 0) {
            // Belt over the bean-validation braces: the nurse-station path has
            // historically shipped DTOs with no validation annotations at all.
            throw new BusinessException("A positive volume in millilitres is required.");
        }
        LocalDateTime observationTime =
            request.getObservationTime() != null ? request.getObservationTime() : LocalDateTime.now();
        if (observationTime.isAfter(LocalDateTime.now())) {
            throw new BusinessException("An intake/output entry cannot be observed in the future.");
        }

        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND + patientId));
        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + hospitalId));
        if (!patient.isRegisteredInHospital(hospitalId)) {
            throw new BusinessException("Patient is not registered at this hospital.");
        }

        IntakeOutputEntry entry = IntakeOutputEntry.builder()
            .patient(patient)
            .hospital(hospital)
            .observationTime(observationTime)
            .documentedAt(LocalDateTime.now())
            .lateEntry(Boolean.TRUE.equals(request.getLateEntry()))
            .route(request.getRoute())
            .category(request.getRoute().getCategory())
            .volumeMl(request.getVolumeMl())
            .notes(request.getNotes())
            .build();

        if (recorderUserId != null) {
            userRepository.findById(recorderUserId).ifPresent(entry::setDocumentedBy);
            staffRepository.findByUserIdAndHospitalId(recorderUserId, hospitalId)
                .ifPresent(entry::setRecordedByStaff);
        }

        return toEntryDto(entryRepository.save(entry));
    }

    @Override
    @Transactional(readOnly = true)
    public IntakeOutputSummaryDTO getSummary(UUID patientId,
                                             UUID hospitalId,
                                             LocalDateTime from,
                                             LocalDateTime to) {
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND + patientId));
        // 404-not-403: a scoped caller asking about a patient their hospital
        // has no active registration for learns nothing, not "exists elsewhere".
        if (hospitalId != null && !patient.isRegisteredInHospital(hospitalId)) {
            throw new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND + patientId);
        }

        LocalDateTime windowTo = to != null ? to : LocalDateTime.now();
        LocalDateTime windowFrom = from != null ? from : windowTo.minus(DEFAULT_WINDOW);
        if (windowFrom.isAfter(windowTo)) {
            throw new BusinessException("The window must start before it ends.");
        }

        List<IntakeOutputEntry> entries =
            entryRepository.findWindow(patientId, hospitalId, windowFrom, windowTo);

        long intake = sumFor(entries, IntakeOutputCategory.INTAKE);
        long output = sumFor(entries, IntakeOutputCategory.OUTPUT);

        return IntakeOutputSummaryDTO.builder()
            .patientId(patientId)
            .windowFrom(windowFrom)
            .windowTo(windowTo)
            .totalIntakeMl(intake)
            .totalOutputMl(output)
            .balanceMl(intake - output)
            .entries(entries.stream().map(this::toEntryDto).toList())
            .build();
    }

    private long sumFor(List<IntakeOutputEntry> entries, IntakeOutputCategory category) {
        return entries.stream()
            .filter(entry -> entry.getCategory() == category)
            .mapToLong(IntakeOutputEntry::getVolumeMl)
            .sum();
    }

    private IntakeOutputSummaryDTO.Entry toEntryDto(IntakeOutputEntry entry) {
        return IntakeOutputSummaryDTO.Entry.builder()
            .id(entry.getId())
            .observationTime(entry.getObservationTime())
            .documentedAt(entry.getDocumentedAt())
            .lateEntry(entry.isLateEntry())
            .category(entry.getCategory())
            .route(entry.getRoute())
            .volumeMl(entry.getVolumeMl())
            .notes(entry.getNotes())
            .recordedByName(resolveRecorderName(entry))
            .build();
    }

    private String resolveRecorderName(IntakeOutputEntry entry) {
        return Optional.ofNullable(entry.getRecordedByStaff())
            .map(staff -> {
                if (staff.getUser() == null) {
                    return staff.getName();
                }
                String first = staff.getUser().getFirstName();
                String last = staff.getUser().getLastName();
                String combined =
                    ((first != null ? first.trim() : "") + " " + (last != null ? last.trim() : "")).trim();
                return combined.isEmpty() ? staff.getName() : combined;
            })
            .orElse(null);
    }
}
