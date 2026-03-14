package com.example.hms.service.impl;

import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.enums.PatientStayStatus;
import com.example.hms.mapper.PatientMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.nurse.NurseDashboardSummaryDTO;
import com.example.hms.repository.MedicationAdministrationRecordRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.service.NurseDashboardService;
import com.example.hms.service.PatientVitalSignService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NurseDashboardServiceImpl implements NurseDashboardService {

    private final PatientHospitalRegistrationRepository registrationRepository;
    private final PatientMapper patientMapper;
    private final PatientVitalSignService patientVitalSignService;
    private final PatientVitalSignRepository patientVitalSignRepository;
    private final MedicationAdministrationRecordRepository marRepository;

    private static final long VITALS_OVERDUE_HOURS = 4;

    @Override
    public List<PatientResponseDTO> getPatientsForNurse(UUID nurseUserId,
                                                        UUID hospitalId,
                                                        LocalDate inhouseDate) {
        List<PatientHospitalRegistration> registrations =
            registrationRepository.findActiveForHospitalWithPatient(hospitalId);

        return registrations.stream()
            .filter(reg -> matchesAssignment(reg, nurseUserId))
            .filter(reg -> isInHouseOn(reg, inhouseDate))
            .sorted(Comparator.comparing(PatientHospitalRegistration::getRegistrationDate,
                Comparator.nullsLast(Comparator.reverseOrder())))
        .map(this::enrichRegistration)
            .toList();
    }

    @Override
    public NurseDashboardSummaryDTO getSummary(UUID nurseUserId, UUID hospitalId) {
        List<PatientResponseDTO> patients = getPatientsForNurse(nurseUserId, hospitalId, null);

        int vitalsOverdue = 0;
        LocalDateTime vitalsCutoff = LocalDateTime.now().minusHours(VITALS_OVERDUE_HOURS);
        for (PatientResponseDTO p : patients) {
            if (p.getId() == null) continue;
            Optional<PatientVitalSign> latest = hospitalId != null
                ? patientVitalSignRepository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(p.getId(), hospitalId)
                : patientVitalSignRepository.findFirstByPatient_IdOrderByRecordedAtDesc(p.getId());
            if (latest.isEmpty() || latest.get().getRecordedAt().isBefore(vitalsCutoff)) {
                vitalsOverdue++;
            }
        }

        long medsOverdue = hospitalId != null
            ? marRepository.countByHospital_IdAndStatusAndScheduledTimeBefore(
                hospitalId, MedicationAdministrationStatus.PENDING, LocalDateTime.now())
            : 0;

        return NurseDashboardSummaryDTO.builder()
            .patientsAssigned(patients.size())
            .vitalsOverdueCount(vitalsOverdue)
            .medsOverdueCount((int) medsOverdue)
            .ordersPendingCount(0)
            .handoffsCount(0)
            .build();
    }

    private boolean matchesAssignment(PatientHospitalRegistration registration, UUID nurseUserId) {
        if (nurseUserId == null) {
            return true;
        }
        UUID readyBy = registration.getReadyByStaffId();
        if (readyBy != null) {
            return nurseUserId.equals(readyBy);
        }
        return true;
    }

    private boolean isInHouseOn(PatientHospitalRegistration registration, LocalDate inhouseDate) {
        if (inhouseDate == null) {
            return true;
        }
        LocalDate registrationDate = registration.getRegistrationDate();
        if (registrationDate == null) {
            return true;
        }
        if (registration.getStayStatus() == PatientStayStatus.DISCHARGED) {
            return false;
        }
        return !registrationDate.isAfter(inhouseDate);
    }

    private PatientResponseDTO enrichRegistration(PatientHospitalRegistration registration) {
        Patient patient = registration.getPatient();
    UUID hospitalId = registration.getHospital() != null ? registration.getHospital().getId() : null;
    PatientResponseDTO dto = patientMapper.toPatientDTO(patient, hospitalId);

        String patientFullName = Optional.ofNullable(patient)
            .map(Patient::getFullName)
            .orElse(null);
        dto.setDisplayName(firstNonNull(trimToNull(registration.getPatientFullName()),
            trimToNull(patientFullName),
            dto.getPatientName()));
        dto.setRoom(firstNonNull(trimToNull(registration.getCurrentBed()), trimToNull(registration.getCurrentRoom())));
        dto.setBed(trimToNull(registration.getCurrentBed()));
        dto.setMrn(registration.getMrn());

        if (dto.getUsername() == null && patient != null && patient.getUser() != null) {
            dto.setUsername(patient.getUser().getUsername());
        }

        PatientResponseDTO.VitalSnapshot vitals = resolveVitals(patient, hospitalId, dto.getLastVitals());
        dto.setLastVitals(vitals);
        if (vitals.getHeartRate() != null) {
            dto.setHr(vitals.getHeartRate());
        }
        if (vitals.getBloodPressure() != null) {
            dto.setBp(vitals.getBloodPressure());
        }
        if (vitals.getSpo2() != null) {
            dto.setSpo2(vitals.getSpo2());
        }

        dto.setFlags(buildFlags(registration, patient));
        dto.setRisks(buildRisks(registration, patient));
        return dto;
    }

    private PatientResponseDTO.VitalSnapshot resolveVitals(Patient patient,
                                                           UUID hospitalId,
                                                           PatientResponseDTO.VitalSnapshot fallback) {
        if (patient == null) {
            return fallback != null ? fallback : PatientResponseDTO.VitalSnapshot.builder().build();
        }
        Optional<PatientResponseDTO.VitalSnapshot> snapshot = patientVitalSignService
            .getLatestSnapshot(patient.getId(), hospitalId);
        return snapshot.orElseGet(() -> fallback != null
            ? fallback
            : PatientResponseDTO.VitalSnapshot.builder().build());
    }

    private List<String> buildFlags(PatientHospitalRegistration registration, Patient patient) {
        List<String> flags = new ArrayList<>();
        if (registration.getStayStatus() == PatientStayStatus.READY_FOR_DISCHARGE) {
            flags.add("Ready for discharge");
        }
        if (patient != null && patient.getAllergies() != null && !patient.getAllergies().isBlank()) {
            flags.add("Allergies noted");
        }
        if (!registration.isActive()) {
            flags.add("Inactive registration");
        }
        return flags;
    }

    private List<String> buildRisks(PatientHospitalRegistration registration, Patient patient) {
        List<String> risks = new ArrayList<>();
        if (registration.getStayStatus() == PatientStayStatus.HOLD) {
            risks.add("Hold status");
        }
        if (registration.getStayStatus() == PatientStayStatus.TRANSFERRED) {
            risks.add("Transferred");
        }
        if (patient != null && patient.getMedicalHistorySummary() != null && !patient.getMedicalHistorySummary().isBlank()) {
            risks.add("Review history");
        }
        return risks;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String firstNonNull(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
