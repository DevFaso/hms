package com.example.hms.service;

import com.example.hms.enums.ConsultationStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.SignatureStatus;
import com.example.hms.model.Appointment;
import com.example.hms.model.Encounter;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.clinical.CriticalStripDTO;
import com.example.hms.payload.dto.clinical.DoctorWorklistItemDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.DigitalSignatureRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.StaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DoctorWorklistServiceImpl implements DoctorWorklistService {

    private final StaffRepository staffRepository;
    private final EncounterRepository encounterRepository;
    private final AppointmentRepository appointmentRepository;
    private final ConsultationRepository consultationRepository;
    private final LabOrderRepository labOrderRepository;
    private final DigitalSignatureRepository digitalSignatureRepository;

    private static final int LONG_WAIT_THRESHOLD_MINUTES = 30;

    @Override
    public CriticalStripDTO getCriticalStrip(UUID userId) {
        log.info("Building critical strip for user: {}", userId);
        Optional<Staff> staffOpt = staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId);
        if (staffOpt.isEmpty()) {
            return CriticalStripDTO.builder().build();
        }
        Staff staff = staffOpt.get();
        UUID staffId = staff.getId();

        // Critical labs: completed lab orders (proxy for results available) — no abnormal flag
        // field exists yet, so we just count completed orders as "review needed"
        long criticalLabs = labOrderRepository.countByOrderingStaff_IdAndStatus(staffId, LabOrderStatus.COMPLETED);

        // Waiting > threshold: active encounters whose elapsed time > 30 min
        List<Encounter> activeEncounters = encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS);
        long waitingLong = activeEncounters.stream()
                .filter(e -> e.getEncounterDate() != null)
                .filter(e -> Duration.between(e.getEncounterDate(), LocalDateTime.now()).toMinutes() > LONG_WAIT_THRESHOLD_MINUTES)
                .count();

        // Pending consults
        long pendingConsults = 0;
        try {
            pendingConsults = consultationRepository
                    .findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED)
                    .size();
        } catch (Exception e) {
            log.debug("Consultation query unavailable: {}", e.getMessage());
        }

        // Unsigned notes / documents to sign
        long unsignedNotes = digitalSignatureRepository.countBySignedBy_IdAndStatus(staffId, SignatureStatus.PENDING);

        // Pending orders needing review: PENDING + IN_PROGRESS lab orders
        long pendingOrderReview = labOrderRepository.countByOrderingStaff_IdAndStatus(staffId, LabOrderStatus.PENDING)
                + labOrderRepository.countByOrderingStaff_IdAndStatus(staffId, LabOrderStatus.IN_PROGRESS);

        return CriticalStripDTO.builder()
                .criticalLabsCount((int) Math.min(criticalLabs, Integer.MAX_VALUE))
                .waitingLongCount((int) Math.min(waitingLong, Integer.MAX_VALUE))
                .pendingConsultsCount((int) Math.min(pendingConsults, Integer.MAX_VALUE))
                .unsignedNotesCount((int) Math.min(unsignedNotes, Integer.MAX_VALUE))
                .pendingOrderReviewCount((int) Math.min(pendingOrderReview, Integer.MAX_VALUE))
                .activeSafetyAlertsCount(0) // stub — alert engine not yet implemented
                .build();
    }

    @Override
    public List<DoctorWorklistItemDTO> getWorklist(UUID userId, String status, String urgency) {
        log.info("Building worklist for user: {} status={} urgency={}", userId, status, urgency);
        Optional<Staff> staffOpt = staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId);
        if (staffOpt.isEmpty()) {
            return Collections.emptyList();
        }
        Staff staff = staffOpt.get();
        UUID staffId = staff.getId();

        Set<UUID> seenPatients = new HashSet<>();
        List<DoctorWorklistItemDTO> items = new ArrayList<>();

        // 1. Active encounters
        List<EncounterStatus> encounterStatuses = List.of(
                EncounterStatus.IN_PROGRESS,
                EncounterStatus.ARRIVED,
                EncounterStatus.SCHEDULED
        );
        for (EncounterStatus es : encounterStatuses) {
            for (Encounter enc : encounterRepository.findByStaff_IdAndStatus(staffId, es)) {
                Patient p = enc.getPatient();
                if (p == null || !seenPatients.add(p.getId())) continue;

                String mappedStatus = mapEncounterStatus(es);
                if (status != null && !status.isEmpty() && !status.equalsIgnoreCase(mappedStatus) && !"ALL".equalsIgnoreCase(status)) {
                    continue;
                }

                items.add(buildWorklistItem(p, enc, mappedStatus));
            }
        }

        // 2. Today's appointments (not yet encountered)
        List<Appointment> todayAppts = appointmentRepository.findByStaff_IdAndAppointmentDate(staffId, LocalDate.now());
        for (Appointment appt : todayAppts) {
            Patient p = appt.getPatient();
            if (p == null || !seenPatients.add(p.getId())) continue;

            String apptStatus = appt.getStatus() != null ? appt.getStatus().name() : "SCHEDULED";
            String mappedStatus = mapAppointmentStatus(apptStatus);
            if (status != null && !status.isEmpty() && !status.equalsIgnoreCase(mappedStatus) && !"ALL".equalsIgnoreCase(status)) {
                continue;
            }

            items.add(DoctorWorklistItemDTO.builder()
                    .patientId(p.getId())
                    .patientName(p.getFirstName() + " " + p.getLastName())
                    .mrn(p.getId().toString())
                    .age(p.getDateOfBirth() != null ? Period.between(p.getDateOfBirth(), LocalDate.now()).getYears() : 0)
                    .sex(p.getGender())
                    .chiefComplaint(appt.getReason())
                    .urgency("ROUTINE")
                    .encounterStatus(mappedStatus)
                    .updatedAt(appt.getUpdatedAt() != null ? appt.getUpdatedAt() : appt.getCreatedAt())
                    .alerts(Collections.emptyList())
                    .build());
        }

        // 3. Pending consults (where this doctor is consultant)
        try {
            var pendingConsults = consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED);
            for (var consult : pendingConsults) {
                Patient p = consult.getPatient();
                if (p == null || !seenPatients.add(p.getId())) continue;

                if (status != null && !status.isEmpty() && !"CONSULTS".equalsIgnoreCase(status) && !"ALL".equalsIgnoreCase(status)) {
                    continue;
                }

                String consultUrgency = consult.getUrgency() != null ? consult.getUrgency().name() : "ROUTINE";
                items.add(DoctorWorklistItemDTO.builder()
                        .patientId(p.getId())
                        .patientName(p.getFirstName() + " " + p.getLastName())
                        .mrn(p.getId().toString())
                        .age(p.getDateOfBirth() != null ? Period.between(p.getDateOfBirth(), LocalDate.now()).getYears() : 0)
                        .sex(p.getGender())
                        .chiefComplaint(consult.getReasonForConsult())
                        .urgency(consultUrgency)
                        .encounterStatus("CONSULT_PENDING")
                        .updatedAt(consult.getRequestedAt())
                        .alerts(Collections.emptyList())
                        .build());
            }
        } catch (Exception e) {
            log.debug("Consultation worklist query unavailable: {}", e.getMessage());
        }

        // Apply urgency filter if provided
        if (urgency != null && !urgency.isEmpty()) {
            items.removeIf(i -> !urgency.equalsIgnoreCase(i.getUrgency()));
        }

        // Sort: urgency descending (EMERGENT > URGENT > ROUTINE > LOW), then waitMinutes descending
        items.sort(Comparator
                .comparingInt((DoctorWorklistItemDTO i) -> urgencyRank(i.getUrgency())).reversed()
                .thenComparing(Comparator.comparingInt((DoctorWorklistItemDTO i) -> i.getWaitMinutes() != null ? i.getWaitMinutes() : 0).reversed()));

        return items;
    }

    private DoctorWorklistItemDTO buildWorklistItem(Patient p, Encounter enc, String mappedStatus) {
        int age = p.getDateOfBirth() != null ? Period.between(p.getDateOfBirth(), LocalDate.now()).getYears() : 0;
        int waitMinutes = enc.getEncounterDate() != null
                ? (int) Math.min(Duration.between(enc.getEncounterDate(), LocalDateTime.now()).toMinutes(), Integer.MAX_VALUE)
                : 0;

        return DoctorWorklistItemDTO.builder()
                .patientId(p.getId())
                .encounterId(enc.getId())
                .patientName(p.getFirstName() + " " + p.getLastName())
                .mrn(p.getId().toString())
                .age(age)
                .sex(p.getGender())
                .chiefComplaint(enc.getNotes())
                .urgency("ROUTINE")
                .encounterStatus(mappedStatus)
                .waitMinutes(waitMinutes)
                .updatedAt(enc.getUpdatedAt() != null ? enc.getUpdatedAt() : enc.getCreatedAt())
                .alerts(Collections.emptyList())
                .build();
    }

    private String mapEncounterStatus(EncounterStatus es) {
        return switch (es) {
            case IN_PROGRESS -> "IN_PROGRESS";
            case ARRIVED -> "CHECKED_IN";
            case SCHEDULED -> "SCHEDULED";
            case COMPLETED -> "COMPLETED";
            case CANCELLED -> "CANCELLED";
        };
    }

    private String mapAppointmentStatus(String apptStatus) {
        return switch (apptStatus) {
            case "CONFIRMED", "SCHEDULED" -> "CHECKED_IN";
            case "IN_PROGRESS" -> "IN_PROGRESS";
            case "COMPLETED" -> "COMPLETED";
            default -> "SCHEDULED";
        };
    }

    private int urgencyRank(String urgency) {
        if (urgency == null) return 0;
        return switch (urgency.toUpperCase()) {
            case "EMERGENT", "EMERGENCY", "STAT" -> 4;
            case "URGENT" -> 3;
            case "ROUTINE" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }
}
