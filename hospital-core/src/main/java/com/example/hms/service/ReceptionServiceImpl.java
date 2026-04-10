package com.example.hms.service;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.model.Appointment;
import com.example.hms.model.AppointmentWaitlist;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientInsurance;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.DuplicateCandidateDTO;
import com.example.hms.payload.dto.EligibilityAttestationRequestDTO;
import com.example.hms.payload.dto.FlowBoardDTO;
import com.example.hms.payload.dto.FrontDeskPatientSnapshotDTO;
import com.example.hms.payload.dto.InsuranceIssueDTO;
import com.example.hms.payload.dto.ReceptionDashboardSummaryDTO;
import com.example.hms.payload.dto.ReceptionQueueItemDTO;
import com.example.hms.payload.dto.WaitlistEntryRequestDTO;
import com.example.hms.payload.dto.WaitlistEntryResponseDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.AppointmentWaitlistRepository;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientInsuranceRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReceptionServiceImpl implements ReceptionService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String STATUS_ARRIVED = "ARRIVED";
    private static final String STATUS_WALK_IN = "WALK_IN";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private final AppointmentRepository appointmentRepo;
    private final EncounterRepository encounterRepo;
    private final PatientInsuranceRepository insuranceRepo;
    private final BillingInvoiceRepository invoiceRepo;
    private final PatientRepository patientRepo;
    private final AppointmentWaitlistRepository waitlistRepo;
    private final HospitalRepository hospitalRepo;
    private final DepartmentRepository departmentRepo;
    private final StaffRepository staffRepo;
    private final AuditEventLogService auditEventLogService;

    // ── MVP 9: Dashboard Summary ─────────────────────────────────────────────

    @Override
    public ReceptionDashboardSummaryDTO getDashboardSummary(LocalDate date, UUID hospitalId) {
        List<Appointment> appointments = appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, date);
        List<UUID> appointmentIds = appointments.stream().map(Appointment::getId).toList();

        List<Encounter> linkedEncounters = appointmentIds.isEmpty()
                ? Collections.emptyList()
                : encounterRepo.findByAppointmentIdIn(appointmentIds);

        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        List<Encounter> walkIns = encounterRepo.findWalkInsForHospitalAndPeriod(hospitalId, dayStart, dayEnd);

        Map<UUID, Encounter> encounterByApptId = linkedEncounters.stream()
                .filter(e -> e.getAppointment() != null)
                .collect(Collectors.toMap(e -> e.getAppointment().getId(), e -> e, (a, b) -> a));

        long arrivedCount = countByStatus(linkedEncounters, EncounterStatus.ARRIVED)
                + countByStatus(walkIns, EncounterStatus.ARRIVED);
        long inProgressCount = countByStatus(linkedEncounters, EncounterStatus.IN_PROGRESS)
                + countByStatus(walkIns, EncounterStatus.IN_PROGRESS);
        long noShowCount = appointments.stream()
                .filter(a -> a.getStatus() == AppointmentStatus.NO_SHOW).count();
        // completedCount: only encounter-side completions to avoid double-counting
        // a scheduled appointment whose encounter is COMPLETED would otherwise add 2.
        long completedCount = countByStatus(linkedEncounters, EncounterStatus.COMPLETED)
                + countByStatus(walkIns, EncounterStatus.COMPLETED);
        // "Scheduled" = appointments with no linked encounter yet, not no-show, not completed
        long scheduledCount = appointments.stream()
                .filter(a -> !encounterByApptId.containsKey(a.getId()))
                .filter(a -> a.getStatus() != AppointmentStatus.NO_SHOW
                        && a.getStatus() != AppointmentStatus.COMPLETED
                        && a.getStatus() != AppointmentStatus.CANCELLED)
                .count();
        // waitingCount: arrived but not yet seen (i.e. not IN_PROGRESS), floored at 0
        long waitingCount = Math.max(0, arrivedCount - inProgressCount);

        return ReceptionDashboardSummaryDTO.builder()
                .date(date)
                .hospitalId(hospitalId)
                .scheduledToday(scheduledCount)
                .arrivedCount(arrivedCount)
                .waitingCount(waitingCount)
                .inProgressCount(inProgressCount)
                .noShowCount(noShowCount)
                .completedCount(completedCount)
                .walkInCount(walkIns.size())
                .build();
    }

    // ── MVP 9: Queue ─────────────────────────────────────────────────────────

    @Override
    public List<ReceptionQueueItemDTO> getQueue(LocalDate date, UUID hospitalId, String status,
                                                  UUID departmentId, UUID providerId) {
        List<Appointment> appointments = applyQueueFilters(
                appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, date),
                departmentId, providerId);

        Map<UUID, Encounter> encounterByApptId = buildEncounterMap(appointments);
        List<ReceptionQueueItemDTO> items = new ArrayList<>();

        for (Appointment appt : appointments) {
            Encounter encounter = encounterByApptId.get(appt.getId());
            String computedStatus = computeStatus(appt, encounter);
            if (excludedByStatusFilter(status, computedStatus)) continue;
            items.add(buildQueueItem(appt, encounter, computedStatus, hospitalId));
        }

        addWalkInItems(items, hospitalId, date, status);
        items.sort(Comparator.comparing(i -> i.getAppointmentTime() == null ? "" : i.getAppointmentTime()));
        return items;
    }

    // ── MVP 9: Patient Snapshot ───────────────────────────────────────────────

    @Override
    public FrontDeskPatientSnapshotDTO getPatientSnapshot(UUID patientId, UUID hospitalId) {
        Patient patient = patientRepo.findById(patientId)
                .orElseThrow(() -> new com.example.hms.exception.ResourceNotFoundException("Patient not found"));

        // MRN from hospital registration
        String mrn = patient.getHospitalRegistrations().stream()
                .filter(r -> r.getHospital() != null && hospitalId.equals(r.getHospital().getId()))
                .map(PatientHospitalRegistration::getMrn)
                .findFirst()
                .orElse(null);

        // Insurance
        List<PatientInsurance> insurances = insuranceRepo.findByPatient_IdAndAssignment_Hospital_Id(patientId, hospitalId);
        LocalDate today = LocalDate.now();
        boolean hasActive = insurances.stream()
                .anyMatch(i -> i.getExpirationDate() == null || !i.getExpirationDate().isBefore(today));
        boolean hasPrimary = insurances.stream().anyMatch(PatientInsurance::isPrimary);
        PatientInsurance primary = insurances.stream()
                .filter(PatientInsurance::isPrimary)
                .findFirst()
                .orElse(insurances.isEmpty() ? null : insurances.get(0));

        FrontDeskPatientSnapshotDTO.InsuranceSummary insuranceSummary = FrontDeskPatientSnapshotDTO.InsuranceSummary.builder()
                .insuranceId(primary != null ? primary.getId() : null)
                .hasActiveCoverage(hasActive)
                .primaryPayer(primary != null ? primary.getProviderName() : null)
                .policyNumber(primary != null ? primary.getPolicyNumber() : null)
                .expiresOn(primary != null ? primary.getExpirationDate() : null)
                .expired(primary != null && primary.getExpirationDate() != null && primary.getExpirationDate().isBefore(today))
                .hasPrimary(hasPrimary)
                .verifiedAt(primary != null ? primary.getVerifiedAt() : null)
                .verifiedBy(primary != null ? primary.getVerifiedBy() : null)
                .build();

        // Billing
        List<BillingInvoice> invoices = invoiceRepo.findByPatient_IdAndHospital_Id(patientId, hospitalId,
                PageRequest.of(0, 100)).getContent();
        List<BillingInvoice> openInvoices = invoices.stream()
                .filter(inv -> inv.getStatus() != InvoiceStatus.PAID
                        && inv.getStatus() != InvoiceStatus.CANCELLED
                        && inv.getStatus() != InvoiceStatus.DRAFT)
                .filter(inv -> balanceDue(inv).compareTo(BigDecimal.ZERO) > 0)
                .toList();
        BigDecimal totalBalance = openInvoices.stream()
                .map(this::balanceDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        FrontDeskPatientSnapshotDTO.BillingSummary billingSummary = FrontDeskPatientSnapshotDTO.BillingSummary.builder()
                .openInvoiceCount(openInvoices.size())
                .totalBalanceDue(totalBalance)
                .build();

        // Alert flags
        boolean incompleteDemographics = patient.getPhoneNumberPrimary() == null || patient.getAddress() == null;
        FrontDeskPatientSnapshotDTO.AlertFlags alerts = FrontDeskPatientSnapshotDTO.AlertFlags.builder()
                .incompleteDemographics(incompleteDemographics)
                .missingInsurance(insurances.isEmpty())
                .expiredInsurance(!insurances.isEmpty() && !hasActive)
                .noPrimaryInsurance(!insurances.isEmpty() && !hasPrimary)
                .outstandingBalance(!openInvoices.isEmpty())
                .build();

        return FrontDeskPatientSnapshotDTO.builder()
                .patientId(patientId)
                .fullName(patient.getFirstName() + " " + patient.getLastName())
                .mrn(mrn)
                .dob(patient.getDateOfBirth())
                .phone(patient.getPhoneNumberPrimary())
                .email(patient.getEmail())
                .address(patient.getAddress())
                .insurance(insuranceSummary)
                .billing(billingSummary)
                .alerts(alerts)
                .build();
    }

    // ── MVP 10: Insurance Issues ──────────────────────────────────────────────

    @Override
    public List<InsuranceIssueDTO> getInsuranceIssues(LocalDate date, UUID hospitalId) {
        List<Appointment> appointments = appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, date);
        LocalDate today = LocalDate.now();
        List<InsuranceIssueDTO> issues = new ArrayList<>();

        for (Appointment appt : appointments) {
            if (appt.getStatus() == AppointmentStatus.CANCELLED
                    || appt.getStatus() == AppointmentStatus.NO_SHOW) {
                continue;
            }
            UUID pid = appt.getPatient().getId();
            List<PatientInsurance> insurances = insuranceRepo
                    .findByPatient_IdAndAssignment_Hospital_Id(pid, hospitalId);

            String issueType = detectInsuranceIssue(insurances, today);
            if (issueType != null) {
                issues.add(buildInsuranceIssueItem(appt, pid, issueType, insurances));
            }
        }
        return issues;
    }

    private String detectInsuranceIssue(List<PatientInsurance> insurances, LocalDate today) {
        if (insurances.isEmpty()) {
            return "MISSING_INSURANCE";
        }
        boolean hasActive = insurances.stream()
                .anyMatch(i -> i.getExpirationDate() == null || !i.getExpirationDate().isBefore(today));
        if (!hasActive) return "EXPIRED_INSURANCE";
        boolean hasPrimary = insurances.stream().anyMatch(PatientInsurance::isPrimary);
        if (!hasPrimary) return "NO_PRIMARY";
        return null;
    }

    private InsuranceIssueDTO buildInsuranceIssueItem(Appointment appt, UUID pid,
                                                       String issueType, List<PatientInsurance> insurances) {
        Patient p = appt.getPatient();
        PatientInsurance relevantInsurance = insurances.isEmpty() ? null : insurances.stream()
                .filter(PatientInsurance::isPrimary)
                .findFirst()
                .orElse(insurances.get(0));
        com.example.hms.patient.dto.PatientInsuranceDto insuranceDto = mapInsuranceDto(relevantInsurance);
        return InsuranceIssueDTO.builder()
                .appointmentId(appt.getId())
                .patientId(pid)
                .patientName(p.getFirstName() + " " + p.getLastName())
                .mrn(null)
                .appointmentTime(appt.getStartTime() != null ? appt.getStartTime().format(TIME_FMT) : null)
                .issueType(issueType)
                .clinicianName(providerName(appt))
                .departmentName(appt.getDepartment() != null ? appt.getDepartment().getName() : null)
                .insurance(insuranceDto)
                .build();
    }

    private com.example.hms.patient.dto.PatientInsuranceDto mapInsuranceDto(PatientInsurance ins) {
        if (ins == null) return null;
        com.example.hms.patient.dto.PatientInsuranceDto dto = new com.example.hms.patient.dto.PatientInsuranceDto();
        dto.setId(ins.getId());
        dto.setProviderName(ins.getProviderName());
        dto.setPolicyNumber(ins.getPolicyNumber());
        dto.setCoverageStart(ins.getEffectiveDate());
        dto.setCoverageEnd(ins.getExpirationDate());
        dto.setPrimaryPlan(ins.isPrimary());
        return dto;
    }

    // ── MVP 10: Payments Pending ──────────────────────────────────────────────

    @Override
    public List<ReceptionQueueItemDTO> getPaymentsPending(LocalDate date, UUID hospitalId) {
        return getQueue(date, hospitalId, "ALL", null, null).stream()
                .filter(ReceptionQueueItemDTO::isHasOutstandingBalance)
                .toList();
    }

    // ── MVP 10: Flow Board ────────────────────────────────────────────────────

    @Override
    public FlowBoardDTO getFlowBoard(LocalDate date, UUID hospitalId, UUID departmentId) {
        List<ReceptionQueueItemDTO> all = getQueue(date, hospitalId, "ALL", departmentId, null);
        return FlowBoardDTO.builder()
                .scheduled(filterByStatus(all, "SCHEDULED"))
                .confirmed(filterByStatus(all, "CONFIRMED"))
                .arrived(filterByStatus(all, STATUS_ARRIVED))
                .inProgress(filterByStatus(all, STATUS_IN_PROGRESS))
                .noShow(filterByStatus(all, "NO_SHOW"))
                .completed(filterByStatus(all, STATUS_COMPLETED))
                .walkIn(filterByStatus(all, STATUS_WALK_IN))
                .build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String computeStatus(Appointment appt, Encounter encounter) {
        if (appt.getStatus() == AppointmentStatus.NO_SHOW) return "NO_SHOW";
        if (appt.getStatus() == AppointmentStatus.CANCELLED) return "CANCELLED";
        if (appt.getStatus() == AppointmentStatus.COMPLETED && encounter == null) return STATUS_COMPLETED;
        if (encounter == null) return appt.getStatus().name();
        return switch (encounter.getStatus()) {
            case ARRIVED -> STATUS_ARRIVED;
            case TRIAGE, WAITING_FOR_PHYSICIAN -> STATUS_ARRIVED;
            case IN_PROGRESS, AWAITING_RESULTS -> STATUS_IN_PROGRESS;
            case READY_FOR_DISCHARGE -> STATUS_IN_PROGRESS;
            case COMPLETED -> STATUS_COMPLETED;
            case CANCELLED -> "CANCELLED";
            default -> appt.getStatus().name();
        };
    }

    private ReceptionQueueItemDTO buildQueueItem(Appointment appt, Encounter encounter,
                                                   String computedStatus, UUID hospitalId) {
        Patient p = appt.getPatient();
        UUID pid = p.getId();

        return ReceptionQueueItemDTO.builder()
                .appointmentId(appt.getId())
                .patientId(pid)
                .patientName(p.getFirstName() + " " + p.getLastName())
                .mrn(null)
                .dateOfBirth(p.getDateOfBirth())
                .appointmentTime(appt.getStartTime() != null ? appt.getStartTime().format(TIME_FMT) : null)
                .providerName(providerName(appt))
                .departmentName(appt.getDepartment() != null ? appt.getDepartment().getName() : null)
                .appointmentReason(appt.getReason())
                .status(computedStatus)
                .waitMinutes(computeWaitMinutes(encounter))
                .encounterId(encounter != null ? encounter.getId() : null)
                .hasInsuranceIssue(detectInsuranceIssue(pid, hospitalId))
                .hasOutstandingBalance(detectOutstandingBalance(pid, hospitalId))
                .build();
    }

    private ReceptionQueueItemDTO buildWalkInQueueItem(Encounter walkIn, String computedStatus) {
        Patient p = walkIn.getPatient();
        UUID walkInHospitalId = walkIn.getHospital() != null ? walkIn.getHospital().getId() : null;
        int waitMins = 0;
        if (walkIn.getStatus() == EncounterStatus.ARRIVED
                || walkIn.getStatus() == EncounterStatus.TRIAGE
                || walkIn.getStatus() == EncounterStatus.WAITING_FOR_PHYSICIAN) {
            waitMins = (int) ChronoUnit.MINUTES.between(walkIn.getEncounterDate(), LocalDateTime.now());
        }
        return ReceptionQueueItemDTO.builder()
                .appointmentId(null)
                .patientId(p.getId())
                .patientName(p.getFirstName() + " " + p.getLastName())
                .mrn(null)
                .dateOfBirth(p.getDateOfBirth())
                .appointmentTime(walkIn.getEncounterDate() != null
                        ? walkIn.getEncounterDate().toLocalTime().format(TIME_FMT) : null)
                .providerName(walkIn.getStaff() != null
                        ? walkIn.getStaff().getUser().getFirstName() + " " + walkIn.getStaff().getUser().getLastName()
                        : null)
                .departmentName(walkIn.getDepartment() != null ? walkIn.getDepartment().getName() : null)
                .appointmentReason("Walk-in")
                .status(computedStatus)
                .waitMinutes(waitMins)
                .encounterId(walkIn.getId())
                .hasInsuranceIssue(walkInHospitalId != null && detectInsuranceIssue(p.getId(), walkInHospitalId))
                .hasOutstandingBalance(walkInHospitalId != null && detectOutstandingBalance(p.getId(), walkInHospitalId))
                .build();
    }

    private String providerName(Appointment appt) {
        if (appt.getStaff() == null || appt.getStaff().getUser() == null) return null;
        return appt.getStaff().getUser().getFirstName() + " " + appt.getStaff().getUser().getLastName();
    }

    private List<Appointment> applyQueueFilters(List<Appointment> appointments,
                                                 UUID departmentId, UUID providerId) {
        if (departmentId != null) {
            appointments = appointments.stream()
                    .filter(a -> a.getDepartment() != null && departmentId.equals(a.getDepartment().getId()))
                    .toList();
        }
        if (providerId != null) {
            appointments = appointments.stream()
                    .filter(a -> a.getStaff() != null && providerId.equals(a.getStaff().getId()))
                    .toList();
        }
        return appointments;
    }

    private Map<UUID, Encounter> buildEncounterMap(List<Appointment> appointments) {
        List<UUID> appointmentIds = appointments.stream().map(Appointment::getId).toList();
        List<Encounter> linkedEncounters = appointmentIds.isEmpty()
                ? Collections.emptyList()
                : encounterRepo.findByAppointmentIdIn(appointmentIds);
        return linkedEncounters.stream()
                .filter(e -> e.getAppointment() != null)
                .collect(Collectors.toMap(e -> e.getAppointment().getId(), e -> e, (a, b) -> a));
    }

    private boolean excludedByStatusFilter(String filter, String computedStatus) {
        return filter != null && !"ALL".equalsIgnoreCase(filter) && !computedStatus.equals(filter);
    }

    private void addWalkInItems(List<ReceptionQueueItemDTO> items, UUID hospitalId,
                                 LocalDate date, String status) {
        if (excludedByStatusFilter(status, STATUS_WALK_IN)
                && excludedByStatusFilter(status, STATUS_ARRIVED)
                && excludedByStatusFilter(status, STATUS_IN_PROGRESS)) {
            return;
        }
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        List<Encounter> walkIns = encounterRepo.findWalkInsForHospitalAndPeriod(hospitalId, dayStart, dayEnd);

        for (Encounter walkIn : walkIns) {
            String computedStatus = computeWalkInStatus(walkIn);
            if (excludedByStatusFilter(status, computedStatus)) continue;
            items.add(buildWalkInQueueItem(walkIn, computedStatus));
        }
    }

    private String computeWalkInStatus(Encounter walkIn) {
        return switch (walkIn.getStatus()) {
            case ARRIVED, TRIAGE, WAITING_FOR_PHYSICIAN -> STATUS_ARRIVED;
            case IN_PROGRESS, AWAITING_RESULTS, READY_FOR_DISCHARGE -> STATUS_IN_PROGRESS;
            case COMPLETED -> STATUS_COMPLETED;
            default -> STATUS_WALK_IN;
        };
    }

    private boolean detectInsuranceIssue(UUID patientId, UUID hospitalId) {
        List<PatientInsurance> insurances = insuranceRepo
                .findByPatient_IdAndAssignment_Hospital_Id(patientId, hospitalId);
        if (insurances.isEmpty()) return true;
        LocalDate today = LocalDate.now();
        boolean hasActive = insurances.stream()
                .anyMatch(i -> i.getExpirationDate() == null || !i.getExpirationDate().isBefore(today));
        return !hasActive || insurances.stream().noneMatch(PatientInsurance::isPrimary);
    }

    private boolean detectOutstandingBalance(UUID patientId, UUID hospitalId) {
        return invoiceRepo.existsOutstandingBalance(patientId, hospitalId);
    }

    private int computeWaitMinutes(Encounter encounter) {
        if (encounter != null && encounter.getStatus() == EncounterStatus.ARRIVED) {
            return (int) ChronoUnit.MINUTES.between(encounter.getEncounterDate(), LocalDateTime.now());
        }
        return 0;
    }

    private BigDecimal balanceDue(BillingInvoice inv) {
        BigDecimal total = inv.getTotalAmount() != null ? inv.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal paid = inv.getAmountPaid() != null ? inv.getAmountPaid() : BigDecimal.ZERO;
        return total.subtract(paid);
    }

    private long countByStatus(List<Encounter> encounters, EncounterStatus status) {
        return encounters.stream().filter(e -> e.getStatus() == status).count();
    }

    private List<ReceptionQueueItemDTO> filterByStatus(List<ReceptionQueueItemDTO> items, String status) {
        return items.stream().filter(i -> status.equals(i.getStatus())).toList();
    }

    // ── MVP 11: Duplicate Candidate Detection ─────────────────────────────────

    @Override
    public List<DuplicateCandidateDTO> getDuplicateCandidates(String name, String dob, String phone,
                                                               UUID hospitalId) {
        String namePattern = (name != null && !name.isBlank())
                ? "%" + name.trim().toLowerCase() + "%" : null;
        String phonePattern = (phone != null && !phone.isBlank())
                ? "%" + phone.trim() + "%" : null;
        List<Patient> candidates = patientRepo.searchPatientsExtended(
                null, namePattern, dob, phonePattern, null, hospitalId, true,
                PageRequest.of(0, 20)).getContent();

        return candidates.stream()
                .map(p -> {
                    int score = computeConfidenceScore(p, name, dob, phone);
                    String mrn = p.getHospitalRegistrations().stream()
                            .filter(r -> r.getHospital() != null && hospitalId.equals(r.getHospital().getId()))
                            .map(PatientHospitalRegistration::getMrn)
                            .findFirst().orElse(null);
                    return DuplicateCandidateDTO.builder()
                            .patientId(p.getId())
                            .fullName(p.getFirstName() + " " + p.getLastName())
                            .mrn(mrn)
                            .dateOfBirth(p.getDateOfBirth())
                            .phone(p.getPhoneNumberPrimary())
                            .email(p.getEmail())
                            .confidenceScore(score)
                            .build();
                })
                .filter(c -> c.getConfidenceScore() >= 40)
                .sorted(Comparator.comparingInt(DuplicateCandidateDTO::getConfidenceScore).reversed())
                .toList();
    }

    private int computeConfidenceScore(Patient p, String name, String dob, String phone) {
        int score = 0;
        if (name != null && !name.isBlank()) {
            String fullName = (p.getFirstName() + " " + p.getLastName()).toLowerCase();
            if (fullName.contains(name.trim().toLowerCase())) score += 50;
        }
        if (dob != null && !dob.isBlank() && p.getDateOfBirth() != null
                && p.getDateOfBirth().toString().equals(dob)) {
            score += 30;
        }
        if (phone != null && !phone.isBlank() && p.getPhoneNumberPrimary() != null
                && p.getPhoneNumberPrimary().contains(phone.trim())) {
            score += 20;
        }
        return Math.min(score, 100);
    }

    // ── MVP 11: Waitlist ──────────────────────────────────────────────────────

    @Override
    @Transactional
    public WaitlistEntryResponseDTO addToWaitlist(WaitlistEntryRequestDTO req, UUID hospitalId,
                                                   String actorUsername) {
        Hospital hospital = hospitalRepo.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));
        Department department = departmentRepo.findById(req.getDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Department not found"));
        Patient patient = patientRepo.findById(req.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));
        Staff provider = (req.getPreferredProviderId() != null)
                ? staffRepo.findById(req.getPreferredProviderId()).orElse(null)
                : null;

        AppointmentWaitlist entry = AppointmentWaitlist.builder()
                .hospital(hospital)
                .department(department)
                .patient(patient)
                .preferredProvider(provider)
                .requestedDateFrom(req.getRequestedDateFrom())
                .requestedDateTo(req.getRequestedDateTo())
                .priority(req.getPriority() != null ? req.getPriority() : "ROUTINE")
                .reason(req.getReason())
                .status("WAITING")
                .createdBy(actorUsername)
                .build();

        entry = waitlistRepo.save(entry);
        return toWaitlistResponse(entry, hospitalId);
    }

    @Override
    public List<WaitlistEntryResponseDTO> getWaitlist(UUID hospitalId, UUID departmentId,
                                                       String status) {
        return waitlistRepo.findByHospitalFiltered(hospitalId, departmentId, status)
                .stream()
                .map(e -> toWaitlistResponse(e, hospitalId))
                .toList();
    }

    @Override
    @Transactional
    public WaitlistEntryResponseDTO offerWaitlistSlot(UUID waitlistId, UUID hospitalId) {
        AppointmentWaitlist entry = waitlistRepo.findByIdAndHospital_Id(waitlistId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Waitlist entry not found"));
        entry.setStatus("OFFERED");
        return toWaitlistResponse(waitlistRepo.save(entry), hospitalId);
    }

    @Override
    @Transactional
    public void closeWaitlistEntry(UUID waitlistId, UUID hospitalId) {
        AppointmentWaitlist entry = waitlistRepo.findByIdAndHospital_Id(waitlistId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Waitlist entry not found"));
        entry.setStatus("CLOSED");
        waitlistRepo.save(entry);
    }

    // ── MVP 11: Eligibility Attestation ──────────────────────────────────────

    @Override
    @Transactional
    public void attestEligibility(UUID insuranceId, UUID hospitalId, String actorUsername,
                                   EligibilityAttestationRequestDTO req) {
        PatientInsurance insurance = insuranceRepo.findByIdAndAssignment_Hospital_Id(insuranceId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Insurance record not found or out of scope"));
        insurance.setVerifiedAt(LocalDateTime.now());
        insurance.setVerifiedBy(actorUsername);
        insurance.setEligibilityNotes(req.getEligibilityNotes());
        insuranceRepo.save(insurance);
    }

    // ── MVP 11: Encounter status update (flow board drag-and-drop) ────────────

    @Override
    @Transactional
    public void updateEncounterStatus(UUID encounterId, EncounterStatus status, UUID hospitalId, String callerUsername) {
        Encounter encounter = encounterRepo.findByIdAndHospital_Id(encounterId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Encounter not found"));

        // Ownership check: RECEPTIONIST and admin roles may move any encounter.
        // DOCTOR / NURSE / MIDWIFE may only move encounters assigned to them.
        boolean isAdminActor = isAdminOrReceptionist(callerUsername);
        if (!isAdminActor) {
            Staff callerStaff = staffRepo.findByUsernameOrLicenseOrRoleCode(callerUsername)
                    .orElse(null);
            UUID encStaffId = encounter.getStaff() != null ? encounter.getStaff().getId() : null;
            if (callerStaff == null || !callerStaff.getId().equals(encStaffId)) {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                        .userName(callerUsername)
                        .eventType(AuditEventType.DATA_UPDATE)
                        .eventDescription("Encounter status update DENIED — encounter not assigned to caller")
                        .resourceId(encounterId.toString())
                        .entityType("ENCOUNTER")
                        .status(AuditStatus.FAILURE)
                        .build());
                throw new AccessDeniedException("You may only update encounter status for your own patients.");
            }
        }

        EncounterStatus previousStatus = encounter.getStatus();
        encounter.setStatus(status);
        encounterRepo.save(encounter);

        auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userName(callerUsername)
                .eventType(AuditEventType.DATA_UPDATE)
                .eventDescription("Encounter status updated from " + previousStatus + " to " + status)
                .resourceId(encounterId.toString())
                .entityType("ENCOUNTER")
                .status(AuditStatus.SUCCESS)
                .build());
    }

    private boolean isAdminOrReceptionist(String username) {
        if (username == null) return false;
        return staffRepo.findByUsernameOrLicenseOrRoleCode(username)
                .map(s -> s.getAssignment() != null && s.getAssignment().getRole() != null
                        && isPrivilegedRoleCode(s.getAssignment().getRole().getCode()))
                .orElse(false);
    }

    private boolean isPrivilegedRoleCode(String roleCode) {
        if (roleCode == null) return false;
        return switch (roleCode) {
            case "ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN", "ROLE_ADMIN", "ROLE_RECEPTIONIST" -> true;
            default -> false;
        };
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private WaitlistEntryResponseDTO toWaitlistResponse(AppointmentWaitlist e, UUID hospitalId) {
        Patient p = e.getPatient();
        String mrn = p.getHospitalRegistrations().stream()
                .filter(r -> r.getHospital() != null && hospitalId.equals(r.getHospital().getId()))
                .map(PatientHospitalRegistration::getMrn)
                .findFirst().orElse(null);
        String provName = null;
        UUID provId = null;
        if (e.getPreferredProvider() != null) {
            provId = e.getPreferredProvider().getId();
            var pu = e.getPreferredProvider().getUser();
            if (pu != null) provName = pu.getFirstName() + " " + pu.getLastName();
        }
        return WaitlistEntryResponseDTO.builder()
                .id(e.getId())
                .hospitalId(e.getHospital().getId())
                .patientId(p.getId())
                .patientName(p.getFirstName() + " " + p.getLastName())
                .mrn(mrn)
                .departmentId(e.getDepartment().getId())
                .departmentName(e.getDepartment().getName())
                .preferredProviderId(provId)
                .preferredProviderName(provName)
                .requestedDateFrom(e.getRequestedDateFrom())
                .requestedDateTo(e.getRequestedDateTo())
                .priority(e.getPriority())
                .reason(e.getReason())
                .status(e.getStatus())
                .offeredAppointmentId(e.getOfferedAppointment() != null ? e.getOfferedAppointment().getId() : null)
                .createdAt(e.getCreatedAt())
                .createdBy(e.getCreatedBy())
                .build();
    }
}
