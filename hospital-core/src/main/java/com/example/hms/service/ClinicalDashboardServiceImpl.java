package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.RefillStatus;
import com.example.hms.model.Encounter;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.clinical.ClinicalAlertDTO;
import com.example.hms.payload.dto.clinical.ClinicalDashboardResponseDTO;
import com.example.hms.payload.dto.clinical.DashboardKPI;
import com.example.hms.payload.dto.clinical.InboxCountsDTO;
import com.example.hms.payload.dto.clinical.OnCallStatusDTO;
import com.example.hms.payload.dto.clinical.RoomedPatientDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.ChatMessageRepository;
import com.example.hms.repository.DigitalSignatureRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.OnCallScheduleRepository;
import com.example.hms.repository.RefillRequestRepository;
import com.example.hms.repository.StaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.hms.patient.dto.PatientResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Implementation of Clinical Dashboard Service.
 * Provides real-time data sourced from the database.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClinicalDashboardServiceImpl implements ClinicalDashboardService {

    private final StaffRepository staffRepository;
    private final AppointmentRepository appointmentRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final RefillRequestRepository refillRequestRepository;
    private final LabOrderRepository labOrderRepository;
    private final DigitalSignatureRepository digitalSignatureRepository;
    private final EncounterRepository encounterRepository;
    private final OnCallScheduleRepository onCallScheduleRepository;

    @Override
    public ClinicalDashboardResponseDTO getClinicalDashboard(UUID userId) {
        log.info("Fetching clinical dashboard for user: {}", userId);

        java.util.Optional<Staff> staffOpt = staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId);
        String specialization = staffOpt.map(Staff::getSpecialization).orElse(null);
        String departmentName = staffOpt
                .map(s -> s.getDepartment() != null ? s.getDepartment().getName() : null)
                .orElse(null);

        return ClinicalDashboardResponseDTO.builder()
                .kpis(generateKPIs(userId))
                .alerts(getCriticalAlerts(userId, 24))
                .inboxCounts(getInboxCounts(userId))
                .onCallStatus(getOnCallStatus(userId))
                .roomedPatients(getRoomedPatients(userId))
                .specialization(specialization)
                .departmentName(departmentName)
                .build();
    }

    @Override
    public List<ClinicalAlertDTO> getCriticalAlerts(UUID userId, int hours) {
        log.info("Fetching critical alerts for user: {} within {} hours", userId, hours);
        // No real alert subsystem exists yet — return empty list rather than fake data
        return Collections.emptyList();
    }

    @Override
    @Transactional
    public void acknowledgeAlert(UUID alertId, UUID userId) {
        log.info("Acknowledging alert: {} by user: {}", alertId, userId);
        // No alert persistence layer yet — no-op
    }

    @Override
    public InboxCountsDTO getInboxCounts(UUID userId) {
        log.info("Fetching inbox counts for user: {}", userId);

        // Unread chat messages sent to this user
        long unreadMessages = chatMessageRepository.countByRecipient_IdAndReadFalse(userId);

        // Pending refill requests for prescriptions belonging to this doctor's staff record
        long pendingRefills = staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId)
                .map(staff -> refillRequestRepository
                        .countByPrescription_Staff_IdAndStatus(staff.getId(), RefillStatus.REQUESTED))
                .orElse(0L);

        // Lab orders placed by this doctor's staff record that are still pending / in-progress
        long pendingResults = staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId)
                .map(staff -> labOrderRepository
                        .countByOrderingStaff_IdAndStatus(staff.getId(), LabOrderStatus.PENDING)
                        + labOrderRepository
                        .countByOrderingStaff_IdAndStatus(staff.getId(), LabOrderStatus.IN_PROGRESS))
                .orElse(0L);

        // Active in-progress encounters assigned to this doctor (open "tasks")
        long tasksToComplete = staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId)
                .map(staff -> (long) encounterRepository
                        .findByStaff_IdAndStatus(staff.getId(), EncounterStatus.IN_PROGRESS).size())
                .orElse(0L);

        // Documents awaiting signature by this doctor's staff record
        // DigitalSignature.signedBy links to Staff, so we look up the staff id first
        long documentsToSign = staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId)
                .map(staff -> digitalSignatureRepository
                        .countBySignedBy_IdAndStatus(staff.getId(),
                                com.example.hms.enums.SignatureStatus.PENDING))
                .orElse(0L);

        return InboxCountsDTO.builder()
                .unreadMessages((int) Math.min(unreadMessages, Integer.MAX_VALUE))
                .pendingRefills((int) Math.min(pendingRefills, Integer.MAX_VALUE))
                .pendingResults((int) Math.min(pendingResults, Integer.MAX_VALUE))
                .tasksToComplete((int) Math.min(tasksToComplete, Integer.MAX_VALUE))
                .documentsToSign((int) Math.min(documentsToSign, Integer.MAX_VALUE))
                .build();
    }

    @Override
    public List<RoomedPatientDTO> getRoomedPatients(UUID userId) {
        log.info("Fetching roomed patients for user: {}", userId);

        return staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId)
                .map(staff -> {
                    List<Encounter> active = encounterRepository
                            .findByStaff_IdAndStatus(staff.getId(), EncounterStatus.IN_PROGRESS);
                    List<RoomedPatientDTO> result = new ArrayList<>();
                    for (Encounter enc : active) {
                        Patient p = enc.getPatient();
                        if (p == null) continue;
                        String name = p.getFirstName() + " " + p.getLastName();
                        int age = p.getDateOfBirth() != null
                                ? Period.between(p.getDateOfBirth(), LocalDate.now()).getYears()
                                : 0;
                        long waitMinutes = enc.getEncounterDate() != null
                                ? java.time.Duration.between(enc.getEncounterDate(), LocalDateTime.now()).toMinutes()
                                : 0;
                        result.add(RoomedPatientDTO.builder()
                                .id(p.getId())
                                .encounterId(enc.getId())
                                .patientName(name)
                                .age(age)
                                .sex(p.getGender())
                                .mrn(p.getId().toString())
                                .chiefComplaint(enc.getNotes())
                                .waitTimeMinutes((int) Math.min(waitMinutes, Integer.MAX_VALUE))
                                .arrivalTime(enc.getEncounterDate())
                                .flags(Collections.emptyList())
                                .prepStatus(RoomedPatientDTO.PrepStatusDTO.builder()
                                        .labsDrawn(false)
                                        .imagingOrdered(false)
                                        .consentSigned(false)
                                        .build())
                                .build());
                    }
                    return result;
                })
                .orElse(Collections.emptyList());
    }

    @Override
    public List<PatientResponse> getRecentPatients(UUID userId) {
        Optional<Staff> staffOpt = staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId);
        if (staffOpt.isEmpty()) return Collections.emptyList();
        Staff staff = staffOpt.get();

        List<PatientResponse> result = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        try {
            encounterRepository.findByStaff_Id(staff.getId()).stream()
                    .filter(e -> e.getPatient() != null)
                    .sorted(Comparator.comparing(
                            e -> e.getEncounterDate() != null ? e.getEncounterDate() : LocalDateTime.MIN,
                            Comparator.reverseOrder()))
                    .filter(e -> seen.add(e.getPatient().getId()))
                    .limit(6)
                    .forEach(e -> {
                        Patient p = e.getPatient();
                        PatientResponse dto = new PatientResponse();
                        dto.setId(p.getId());
                        dto.setMrn(p.getId().toString());
                        dto.setFirstName(p.getFirstName());
                        dto.setLastName(p.getLastName());
                        dto.setDateOfBirth(p.getDateOfBirth());
                        dto.setGender(p.getGender());
                        dto.setPhone(p.getPhoneNumberPrimary());
                        if (e.getEncounterDate() != null) {
                            dto.setLastEncounterDate(e.getEncounterDate().toString());
                        }
                        dto.setLastLocation(e.getDepartment() != null ? e.getDepartment().getName() : null);
                        result.add(dto);
                    });
        } catch (Exception ex) {
            log.debug("Recent patients query error: {}", ex.getMessage());
        }
        return result;
    }

    @Override
    public OnCallStatusDTO getOnCallStatus(UUID userId) {
        log.info("Fetching on-call status for user: {}", userId);
        try {
            Optional<Staff> staffOpt = staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId);
            if (staffOpt.isEmpty()) {
                return OnCallStatusDTO.builder().isOnCall(false).build();
            }
            UUID staffId = staffOpt.get().getId();
            LocalDateTime now = LocalDateTime.now();
            var schedules = onCallScheduleRepository.findActiveByStaffIdAt(staffId, now);
            if (!schedules.isEmpty()) {
                var entry = schedules.get(0);
                return OnCallStatusDTO.builder()
                        .isOnCall(true)
                        .shiftStart(entry.getStartTime())
                        .shiftEnd(entry.getEndTime())
                        .build();
            }
        } catch (Exception e) {
            log.debug("On-call schedule query unavailable: {}", e.getMessage());
        }
        return OnCallStatusDTO.builder().isOnCall(false).build();
    }

    /**
     * Generate KPI metrics for the dashboard from real database data.
     */
    private List<DashboardKPI> generateKPIs(UUID userId) {
        List<DashboardKPI> kpis = new ArrayList<>();

        // Resolve the primary staff record for this user
        java.util.Optional<Staff> staffOpt = staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId);

        // --- Patients / Appointments today ---
        int appointmentsToday = staffOpt
                .map(staff -> appointmentRepository
                        .findByStaff_IdAndAppointmentDate(staff.getId(), LocalDate.now()).size())
                .orElse(0);

        kpis.add(DashboardKPI.builder()
                .key("appointments")
                .label("Appointments Today")
                .value(appointmentsToday)
                .unit("scheduled")
                .trend("stable")
                .build());

        // --- Pending lab results ---
        long pendingLabs = staffOpt
                .map(staff -> labOrderRepository
                        .countByOrderingStaff_IdAndStatus(staff.getId(), LabOrderStatus.PENDING)
                        + labOrderRepository
                        .countByOrderingStaff_IdAndStatus(staff.getId(), LabOrderStatus.IN_PROGRESS))
                .orElse(0L);

        kpis.add(DashboardKPI.builder()
                .key("pending_results")
                .label("Pending Results")
                .value((int) Math.min(pendingLabs, Integer.MAX_VALUE))
                .unit("results")
                .trend(pendingLabs > 0 ? "up" : "stable")
                .build());

        // --- Active encounters (patients currently being seen) ---
        int activeEncounters = staffOpt
                .map(staff -> encounterRepository
                        .findByStaff_IdAndStatus(staff.getId(), EncounterStatus.IN_PROGRESS).size())
                .orElse(0);

        kpis.add(DashboardKPI.builder()
                .key("patients_today")
                .label("Active Patients")
                .value(activeEncounters)
                .unit("patients")
                .trend("stable")
                .build());

        // --- Pending refill requests ---
        long pendingRefills = staffOpt
                .map(staff -> refillRequestRepository
                        .countByPrescription_Staff_IdAndStatus(staff.getId(), RefillStatus.REQUESTED))
                .orElse(0L);

        kpis.add(DashboardKPI.builder()
                .key("tasks")
                .label("Refill Requests")
                .value((int) Math.min(pendingRefills, Integer.MAX_VALUE))
                .unit("requests")
                .trend(pendingRefills > 0 ? "up" : "stable")
                .build());

        return kpis;
    }
}
