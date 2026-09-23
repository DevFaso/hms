package com.example.hms.service;

import com.example.hms.utility.ElapsedTime;
import com.example.hms.enums.AbnormalFlag;
import com.example.hms.enums.ConsultationStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.SignatureStatus;
import com.example.hms.enums.AdmissionStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Appointment;
import com.example.hms.model.Encounter;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.clinical.CriticalStripDTO;
import com.example.hms.payload.dto.clinical.DoctorWorklistItemDTO;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.DigitalSignatureRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.StaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DoctorWorklistServiceImpl implements DoctorWorklistService {

    private static final String STATUS_CHECKED_IN = "CHECKED_IN";

    // Sonar S1192 (Pattern 5 of docs/SonarQubeInstructions.md): these
    // four status / urgency strings are emitted to the worklist DTO
    // (frontend contract is String-typed, so we can't switch to the
    // matching enums without widening the API). One constant per value.
    // Naming follows the existing STATUS_CHECKED_IN sibling above.
    private static final String STATUS_SCHEDULED = "SCHEDULED";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String URGENCY_ROUTINE = "ROUTINE";

    private final StaffRepository staffRepository;
    private final EncounterRepository encounterRepository;
    private final AppointmentRepository appointmentRepository;
    private final ConsultationRepository consultationRepository;
    private final LabOrderRepository labOrderRepository;
    private final LabResultRepository labResultRepository;
    private final DigitalSignatureRepository digitalSignatureRepository;
    private final PatientVitalSignRepository patientVitalSignRepository;
    private final AdmissionRepository admissionRepository;

    private static final int LONG_WAIT_THRESHOLD_MINUTES = 30;

    /**
     * How far back the critical strip looks for unacknowledged criticals (B15).
     * A critical value from months ago is a chart fact, not a live alert; the
     * escalation sweep, not this tile, chases those.
     *
     * <p>Measured on the ROW's own {@code createdAt}, not on {@code resultDate}:
     * the result date is supplied by the caller (OBX-14 on an HL7 result, a
     * form field on a manual one), so a result filed today with a back-dated
     * result date would never reach the tile it exists for.
     */
    static final int SAFETY_ALERT_WINDOW_DAYS = 30;

    /**
     * Lab-order states that still belong to the laboratory — what the
     * "orders needing review" tile counts.
     *
     * <p>It used to count PENDING + IN_PROGRESS only. Once the lifecycle
     * started moving orders on the specimen events (and V163 drained the old
     * rows), those two states became transient or unused, so the tile would
     * have shown a permanent 0 from the deploy onwards.
     */
    /**
     * How far back the "orders needing review" tile looks.
     *
     * <p>Same reason as {@link #SAFETY_ALERT_WINDOW_DAYS}, and the defect the
     * sibling count already had fixed: ORDERED has no natural end, so without
     * a floor the tile counted every order this doctor ever placed that the
     * laboratory never collected — a number that only grows.
     */
    static final int PENDING_ORDER_WINDOW_DAYS = 30;

    private static final java.util.Set<LabOrderStatus> AWAITING_LAB =
            java.util.EnumSet.of(
                    LabOrderStatus.ORDERED,
                    LabOrderStatus.PENDING,
                    LabOrderStatus.COLLECTED,
                    LabOrderStatus.RECEIVED,
                    LabOrderStatus.IN_PROGRESS);

    @Override
    public CriticalStripDTO getCriticalStrip(UUID userId) {
        log.info("Building critical strip for user: {}", userId);
        Optional<Staff> staffOpt = staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId);
        if (staffOpt.isEmpty()) {
            return CriticalStripDTO.builder().build();
        }
        Staff staff = staffOpt.get();
        UUID staffId = staff.getId();

        // Critical labs still needing this doctor's attention: flagged CRITICAL
        // by the lab, not yet acknowledged, and recent (B15). The old count was
        // every CRITICAL result ever filed for the staff, so the tile never
        // went back down.
        // Explicit zone: the floor is compared against stored timestamps, so
        // which clock it is read from must be stated, not inherited.
        LocalDateTime safetyAlertFloor = LocalDateTime.now(java.time.ZoneId.systemDefault())
                .minusDays(SAFETY_ALERT_WINDOW_DAYS);
        long criticalLabs = labResultRepository
                .countByLabOrder_OrderingStaff_IdAndAbnormalFlagAndAcknowledgedFalseAndCreatedAtAfter(
                        staffId, AbnormalFlag.CRITICAL, safetyAlertFloor);

        // Waiting > threshold: active encounters whose elapsed time > 30 min
        List<Encounter> activeEncounters = encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS);
        long waitingLong = activeEncounters.stream()
                .filter(e -> e.getEncounterDate() != null)
                .filter(e -> ElapsedTime.minutesBetween(e.getEncounterDate(), LocalDateTime.now()) > LONG_WAIT_THRESHOLD_MINUTES)
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

        // Orders still with the laboratory, in every state the lifecycle
        // actually produces (see AWAITING_LAB), placed recently enough to be
        // a live tile rather than a growing archive.
        long pendingOrderReview = labOrderRepository.countByOrderingStaff_IdAndStatusInAndOrderDatetimeAfter(
                staffId, AWAITING_LAB,
                LocalDateTime.now(java.time.ZoneId.systemDefault()).minusDays(PENDING_ORDER_WINDOW_DAYS));

        return CriticalStripDTO.builder()
                .criticalLabsCount((int) Math.min(criticalLabs, Integer.MAX_VALUE))
                .waitingLongCount((int) Math.min(waitingLong, Integer.MAX_VALUE))
                .pendingConsultsCount((int) Math.min(pendingConsults, Integer.MAX_VALUE))
                .unsignedNotesCount((int) Math.min(unsignedNotes, Integer.MAX_VALUE))
                .pendingOrderReviewCount((int) Math.min(pendingOrderReview, Integer.MAX_VALUE))
                .activeSafetyAlertsCount((int) Math.min(criticalLabs, Integer.MAX_VALUE)) // CRITICAL lab results = real safety alerts
                .build();
    }

    @Override
    public List<DoctorWorklistItemDTO> getWorklist(UUID userId, String status, String urgency, LocalDate date) {
        log.info("Building worklist for user: {} status={} urgency={} date={}", userId, status, urgency, date);
        Optional<Staff> staffOpt = staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId);
        if (staffOpt.isEmpty()) {
            return Collections.emptyList();
        }
        Staff staff = staffOpt.get();
        UUID staffId = staff.getId();

        Set<UUID> seenPatients = new HashSet<>();
        List<DoctorWorklistItemDTO> items = new ArrayList<>();

        Map<UUID, String> patientRoomBed = buildPatientRoomBedMap(staffId);

        addEncounterItems(staffId, status, seenPatients, items, patientRoomBed);
        addAppointmentItems(staffId, status, date, seenPatients, items);
        addConsultItems(staffId, status, seenPatients, items);

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

    private Map<UUID, String> buildPatientRoomBedMap(UUID staffId) {
        Map<UUID, String> patientRoomBed = new HashMap<>();
        try {
            admissionRepository.findByAdmittingProviderIdOrderByAdmissionDateTimeDesc(staffId)
                    .stream()
                    .filter(a -> a.getStatus() == AdmissionStatus.ACTIVE)
                    .forEach(a -> patientRoomBed.putIfAbsent(a.getPatient().getId(), a.getRoomBed()));
        } catch (Exception e) {
            log.debug("Admission room/bed lookup unavailable: {}", e.getMessage());
        }
        return patientRoomBed;
    }

    private void addEncounterItems(UUID staffId, String status, Set<UUID> seenPatients,
                                   List<DoctorWorklistItemDTO> items, Map<UUID, String> patientRoomBed) {
        List<EncounterStatus> encounterStatuses = List.of(
                EncounterStatus.IN_PROGRESS, EncounterStatus.ARRIVED, EncounterStatus.SCHEDULED,
                EncounterStatus.TRIAGE, EncounterStatus.WAITING_FOR_PHYSICIAN,
                EncounterStatus.AWAITING_RESULTS, EncounterStatus.READY_FOR_DISCHARGE);
        for (EncounterStatus es : encounterStatuses) {
            for (Encounter enc : encounterRepository.findByStaff_IdAndStatus(staffId, es)) {
                Patient p = enc.getPatient();
                if (p == null || !seenPatients.add(p.getId())) continue;

                String mappedStatus = mapEncounterStatus(es);
                if (!matchesStatusFilter(status, mappedStatus)) continue;

                items.add(buildWorklistItem(p, enc, mappedStatus, patientRoomBed));
            }
        }
    }

    private void addAppointmentItems(UUID staffId, String status, LocalDate date,
                                     Set<UUID> seenPatients, List<DoctorWorklistItemDTO> items) {
        LocalDate worklistDate = date != null ? date : LocalDate.now();
        List<Appointment> todayAppts = appointmentRepository.findByStaff_IdAndAppointmentDate(staffId, worklistDate);
        for (Appointment appt : todayAppts) {
            Patient p = appt.getPatient();
            if (p == null || !seenPatients.add(p.getId())) continue;

            String apptStatus = appt.getStatus() != null ? appt.getStatus().name() : STATUS_SCHEDULED;
            String mappedStatus = mapAppointmentStatus(apptStatus);
            if (!matchesStatusFilter(status, mappedStatus)) continue;

            items.add(buildAppointmentWorklistItem(p, appt, mappedStatus));
        }
    }

    private void addConsultItems(UUID staffId, String status, Set<UUID> seenPatients,
                                 List<DoctorWorklistItemDTO> items) {
        try {
            var pendingConsults = consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED);
            for (var consult : pendingConsults) {
                Patient p = consult.getPatient();
                if (p == null || !seenPatients.add(p.getId())) continue;

                if (status != null && !status.isEmpty() && !"CONSULTS".equalsIgnoreCase(status) && !"ALL".equalsIgnoreCase(status)) {
                    continue;
                }

                items.add(buildConsultWorklistItem(p, consult));
            }
        } catch (Exception e) {
            log.warn("Consultation worklist query unavailable: {}", e.getMessage());
        }
    }

    private boolean matchesStatusFilter(String statusFilter, String candidateStatus) {
        return statusFilter == null || statusFilter.isEmpty()
                || statusFilter.equalsIgnoreCase(candidateStatus) || "ALL".equalsIgnoreCase(statusFilter);
    }

    private DoctorWorklistItemDTO buildAppointmentWorklistItem(Patient p, Appointment appt, String mappedStatus) {
        UUID apptHospitalId = appt.getHospital() != null ? appt.getHospital().getId() : null;
        return DoctorWorklistItemDTO.builder()
                .patientId(p.getId())
                .patientName(p.getFirstName() + " " + p.getLastName())
                .mrn(p.getMrnForHospital(apptHospitalId))
                .age(computeAge(p))
                .sex(p.getGender())
                .chiefComplaint(appt.getReason())
                .urgency(URGENCY_ROUTINE)
                .encounterStatus(mappedStatus)
                .updatedAt(appt.getUpdatedAt() != null ? appt.getUpdatedAt() : appt.getCreatedAt())
                .alerts(Collections.emptyList())
                .build();
    }

    private DoctorWorklistItemDTO buildConsultWorklistItem(Patient p, com.example.hms.model.Consultation consult) {
        String consultUrgency = consult.getUrgency() != null ? consult.getUrgency().name() : URGENCY_ROUTINE;
        UUID consultHospitalId = consult.getHospital() != null ? consult.getHospital().getId() : null;
        return DoctorWorklistItemDTO.builder()
                .patientId(p.getId())
                .patientName(p.getFirstName() + " " + p.getLastName())
                .mrn(p.getMrnForHospital(consultHospitalId))
                .age(computeAge(p))
                .sex(p.getGender())
                .chiefComplaint(consult.getReasonForConsult())
                .urgency(consultUrgency)
                .encounterStatus("CONSULTATION")
                .updatedAt(consult.getRequestedAt())
                .alerts(Collections.emptyList())
                .build();
    }

    private int computeAge(Patient p) {
        return p.getDateOfBirth() != null ? Period.between(p.getDateOfBirth(), LocalDate.now()).getYears() : 0;
    }

    private DoctorWorklistItemDTO buildWorklistItem(Patient p, Encounter enc, String mappedStatus, Map<UUID, String> patientRoomBed) {
        int age = computeAge(p);
        int waitMinutes = enc.getEncounterDate() != null
                ? (int) Math.min(ElapsedTime.minutesBetween(enc.getEncounterDate(), LocalDateTime.now()), Integer.MAX_VALUE)
                : 0;

        String location = enc.getDepartment() != null ? enc.getDepartment().getName() : null;
        String[] roomBed = parseRoomBed(patientRoomBed.get(p.getId()));
        String latestVitalsSummary = fetchVitalsSummary(p.getId());

        UUID encHospitalId = enc.getHospital() != null ? enc.getHospital().getId() : null;
        return DoctorWorklistItemDTO.builder()
                .patientId(p.getId())
                .encounterId(enc.getId())
                .patientName(p.getFirstName() + " " + p.getLastName())
                .mrn(p.getMrnForHospital(encHospitalId))
                .age(age)
                .sex(p.getGender())
                .room(roomBed[0])
                .bed(roomBed[1])
                .location(location)
                .latestVitalsSummary(latestVitalsSummary)
                .chiefComplaint(enc.getNotes())
                .urgency(enc.getUrgency() != null ? enc.getUrgency().name() : URGENCY_ROUTINE)
                .encounterStatus(mappedStatus)
                .waitMinutes(waitMinutes)
                .updatedAt(enc.getUpdatedAt() != null ? enc.getUpdatedAt() : enc.getCreatedAt())
                .alerts(Collections.emptyList())
                .build();
    }

    private String[] parseRoomBed(String rawRoomBed) {
        String room = null;
        String bed = null;
        if (rawRoomBed != null && !rawRoomBed.isBlank()) {
            int slash = rawRoomBed.indexOf('/');
            if (slash > 0) {
                room = rawRoomBed.substring(0, slash).trim();
                bed  = rawRoomBed.substring(slash + 1).trim();
            } else {
                room = rawRoomBed.trim();
            }
        }
        return new String[]{room, bed};
    }

    private String fetchVitalsSummary(UUID patientId) {
        try {
            Optional<PatientVitalSign> vOpt =
                    patientVitalSignRepository.findFirstByPatient_IdOrderByRecordedAtDesc(patientId);
            if (vOpt.isPresent()) {
                PatientVitalSign v = vOpt.get();
                List<String> vParts = new ArrayList<>();
                if (v.getHeartRateBpm() != null) vParts.add("HR: " + v.getHeartRateBpm());
                if (v.getSystolicBpMmHg() != null && v.getDiastolicBpMmHg() != null)
                    vParts.add("BP: " + v.getSystolicBpMmHg() + "/" + v.getDiastolicBpMmHg());
                if (v.getSpo2Percent() != null) vParts.add("SpO2: " + v.getSpo2Percent() + "%");
                if (!vParts.isEmpty()) return String.join(" \u00b7 ", vParts);
            }
        } catch (Exception ex) {
            log.debug("Vitals query error for patient {}: {}", patientId, ex.getMessage());
        }
        return null;
    }

    private String mapEncounterStatus(EncounterStatus es) {
        return switch (es) {
            case ARRIVED -> STATUS_CHECKED_IN;
            case SCHEDULED -> STATUS_SCHEDULED;
            case TRIAGE -> "TRIAGE";
            case WAITING_FOR_PHYSICIAN -> "WAITING";
            case IN_PROGRESS -> STATUS_IN_PROGRESS;
            case AWAITING_RESULTS -> STATUS_IN_PROGRESS;
            case READY_FOR_DISCHARGE -> STATUS_IN_PROGRESS;
            case COMPLETED -> STATUS_COMPLETED;
            case CANCELLED -> "CANCELLED";
        };
    }

    private String mapAppointmentStatus(String apptStatus) {
        return switch (apptStatus) {
            case "CONFIRMED", STATUS_SCHEDULED -> STATUS_CHECKED_IN;
            case STATUS_IN_PROGRESS -> STATUS_IN_PROGRESS;
            case STATUS_COMPLETED -> STATUS_COMPLETED;
            default -> STATUS_SCHEDULED;
        };
    }

    private int urgencyRank(String urgency) {
        if (urgency == null) return 0;
        return switch (urgency.toUpperCase()) {
            case "EMERGENT", "EMERGENCY", "STAT" -> 4;
            case "URGENT" -> 3;
            case URGENCY_ROUTINE -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }
}
