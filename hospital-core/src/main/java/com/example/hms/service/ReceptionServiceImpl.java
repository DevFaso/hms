package com.example.hms.service;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.exception.ResourceNotFoundException;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

    private final AppointmentRepository appointmentRepo;
    private final EncounterRepository encounterRepo;
    private final PatientInsuranceRepository insuranceRepo;
    private final BillingInvoiceRepository invoiceRepo;
    private final PatientRepository patientRepo;
    private final AppointmentWaitlistRepository waitlistRepo;
    private final HospitalRepository hospitalRepo;
    private final DepartmentRepository departmentRepo;
    private final StaffRepository staffRepo;

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
        long completedCount = appointments.stream()
                .filter(a -> a.getStatus() == AppointmentStatus.COMPLETED).count()
                + countByStatus(linkedEncounters, EncounterStatus.COMPLETED)
                + countByStatus(walkIns, EncounterStatus.COMPLETED);
        // "Scheduled" = appointments with no linked encounter yet, not no-show, not completed
        long scheduledCount = appointments.stream()
                .filter(a -> !encounterByApptId.containsKey(a.getId()))
                .filter(a -> a.getStatus() != AppointmentStatus.NO_SHOW
                        && a.getStatus() != AppointmentStatus.COMPLETED
                        && a.getStatus() != AppointmentStatus.CANCELLED)
                .count();

        return ReceptionDashboardSummaryDTO.builder()
                .date(date)
                .hospitalId(hospitalId)
                .scheduledToday(scheduledCount)
                .arrivedCount(arrivedCount)
                .waitingCount(arrivedCount) // waiting = arrived but not yet IN_PROGRESS
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
        List<Appointment> appointments = appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, date);

        // Apply department / provider filters
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

        List<UUID> appointmentIds = appointments.stream().map(Appointment::getId).toList();
        List<Encounter> linkedEncounters = appointmentIds.isEmpty()
                ? Collections.emptyList()
                : encounterRepo.findByAppointmentIdIn(appointmentIds);
        Map<UUID, Encounter> encounterByApptId = linkedEncounters.stream()
                .filter(e -> e.getAppointment() != null)
                .collect(Collectors.toMap(e -> e.getAppointment().getId(), e -> e, (a, b) -> a));

        // Walk-in encounters (no appointment binding, today)
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        List<Encounter> walkIns = encounterRepo.findWalkInsForHospitalAndPeriod(hospitalId, dayStart, dayEnd);

        List<ReceptionQueueItemDTO> items = new ArrayList<>();

        // Appointment-based items
        for (Appointment appt : appointments) {
            Encounter encounter = encounterByApptId.get(appt.getId());
            String computedStatus = computeStatus(appt, encounter);
            if (!"ALL".equalsIgnoreCase(status) && status != null && !computedStatus.equals(status)) {
                continue;
            }
            items.add(buildQueueItem(appt, encounter, computedStatus, date, hospitalId));
        }

        // Walk-in items (no status filter restricts these unless filter = specific non-walk-in status)
        if (status == null || "ALL".equalsIgnoreCase(status) || "ARRIVED".equals(status)
                || "IN_PROGRESS".equals(status) || "WALK_IN".equals(status)) {
            for (Encounter walkIn : walkIns) {
                String computedStatus = "WALK_IN";
                if (walkIn.getStatus() == EncounterStatus.IN_PROGRESS) computedStatus = "IN_PROGRESS";
                else if (walkIn.getStatus() == EncounterStatus.COMPLETED) computedStatus = "COMPLETED";
                if (status != null && !"ALL".equalsIgnoreCase(status) && !computedStatus.equals(status)) {
                    continue;
                }
                items.add(buildWalkInQueueItem(walkIn, computedStatus));
            }
        }

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

            String issueType = null;
            if (insurances.isEmpty()) {
                issueType = "MISSING_INSURANCE";
            } else {
                boolean hasActive = insurances.stream()
                        .anyMatch(i -> i.getExpirationDate() == null || !i.getExpirationDate().isBefore(today));
                boolean hasPrimary = insurances.stream().anyMatch(PatientInsurance::isPrimary);
                if (!hasActive) issueType = "EXPIRED_INSURANCE";
                else if (!hasPrimary) issueType = "NO_PRIMARY";
            }

            if (issueType != null) {
                Patient p = appt.getPatient();
                // Embed the first relevant insurance record for the panel (null for MISSING)
                PatientInsurance relevantInsurance = insurances.isEmpty() ? null : insurances.stream()
                        .filter(i -> i.isPrimary())
                        .findFirst()
                        .orElse(insurances.get(0));
                com.example.hms.patient.dto.PatientInsuranceDto insuranceDto = null;
                if (relevantInsurance != null) {
                    insuranceDto = new com.example.hms.patient.dto.PatientInsuranceDto();
                    insuranceDto.setId(relevantInsurance.getId());
                    insuranceDto.setProviderName(relevantInsurance.getProviderName());
                    insuranceDto.setPolicyNumber(relevantInsurance.getPolicyNumber());
                    insuranceDto.setCoverageStart(relevantInsurance.getEffectiveDate());
                    insuranceDto.setCoverageEnd(relevantInsurance.getExpirationDate());
                    insuranceDto.setPrimaryPlan(relevantInsurance.isPrimary());
                }
                issues.add(InsuranceIssueDTO.builder()
                        .appointmentId(appt.getId())
                        .patientId(pid)
                        .patientName(p.getFirstName() + " " + p.getLastName())
                        .mrn(null)
                        .appointmentTime(appt.getStartTime() != null ? appt.getStartTime().format(TIME_FMT) : null)
                        .issueType(issueType)
                        .clinicianName(providerName(appt))
                        .departmentName(appt.getDepartment() != null ? appt.getDepartment().getName() : null)
                        .insurance(insuranceDto)
                        .build());
            }
        }
        return issues;
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
                .arrived(filterByStatus(all, "ARRIVED"))
                .inProgress(filterByStatus(all, "IN_PROGRESS"))
                .noShow(filterByStatus(all, "NO_SHOW"))
                .completed(filterByStatus(all, "COMPLETED"))
                .walkIn(filterByStatus(all, "WALK_IN"))
                .build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String computeStatus(Appointment appt, Encounter encounter) {
        if (appt.getStatus() == AppointmentStatus.NO_SHOW) return "NO_SHOW";
        if (appt.getStatus() == AppointmentStatus.CANCELLED) return "CANCELLED";
        if (appt.getStatus() == AppointmentStatus.COMPLETED && encounter == null) return "COMPLETED";
        if (encounter == null) return appt.getStatus().name();
        return switch (encounter.getStatus()) {
            case ARRIVED -> "ARRIVED";
            case IN_PROGRESS -> "IN_PROGRESS";
            case COMPLETED -> "COMPLETED";
            case CANCELLED -> "CANCELLED";
            default -> appt.getStatus().name();
        };
    }

    private ReceptionQueueItemDTO buildQueueItem(Appointment appt, Encounter encounter,
                                                   String computedStatus, LocalDate date, UUID hospitalId) {
        Patient p = appt.getPatient();
        UUID pid = p.getId();

        List<PatientInsurance> insurances = insuranceRepo
                .findByPatient_IdAndAssignment_Hospital_Id(pid, hospitalId);
        LocalDate today = LocalDate.now();
        boolean hasInsuranceIssue = insurances.isEmpty()
                || insurances.stream().noneMatch(i -> i.getExpirationDate() == null || !i.getExpirationDate().isBefore(today))
                || insurances.stream().noneMatch(PatientInsurance::isPrimary);

        List<BillingInvoice> openInvoices = invoiceRepo
                .findByPatient_IdAndHospital_Id(pid, hospitalId, PageRequest.of(0, 5)).getContent()
                .stream()
                .filter(inv -> inv.getStatus() != InvoiceStatus.PAID
                        && inv.getStatus() != InvoiceStatus.CANCELLED
                        && inv.getStatus() != InvoiceStatus.DRAFT)
                .filter(inv -> balanceDue(inv).compareTo(BigDecimal.ZERO) > 0)
                .toList();

        int waitMins = 0;
        if (encounter != null && encounter.getStatus() == EncounterStatus.ARRIVED) {
            waitMins = (int) ChronoUnit.MINUTES.between(encounter.getEncounterDate(), LocalDateTime.now());
        }

        return ReceptionQueueItemDTO.builder()
                .appointmentId(appt.getId())
                .patientId(pid)
                .patientName(p.getFirstName() + " " + p.getLastName())
                .mrn(null) // populated lazily via snapshot endpoint
                .dateOfBirth(p.getDateOfBirth())
                .appointmentTime(appt.getStartTime() != null ? appt.getStartTime().format(TIME_FMT) : null)
                .providerName(providerName(appt))
                .departmentName(appt.getDepartment() != null ? appt.getDepartment().getName() : null)
                .appointmentReason(appt.getReason())
                .status(computedStatus)
                .waitMinutes(waitMins)
                .encounterId(encounter != null ? encounter.getId() : null)
                .hasInsuranceIssue(hasInsuranceIssue)
                .hasOutstandingBalance(!openInvoices.isEmpty())
                .build();
    }

    private ReceptionQueueItemDTO buildWalkInQueueItem(Encounter walkIn, String computedStatus) {
        Patient p = walkIn.getPatient();
        int waitMins = 0;
        if (walkIn.getStatus() == EncounterStatus.ARRIVED) {
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
                .hasInsuranceIssue(false)
                .hasOutstandingBalance(false)
                .build();
    }

    private String providerName(Appointment appt) {
        if (appt.getStaff() == null || appt.getStaff().getUser() == null) return null;
        return appt.getStaff().getUser().getFirstName() + " " + appt.getStaff().getUser().getLastName();
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
    public void updateEncounterStatus(UUID encounterId, EncounterStatus status, UUID hospitalId) {
        Encounter encounter = encounterRepo.findByIdAndHospital_Id(encounterId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Encounter not found"));
        encounter.setStatus(status);
        encounterRepo.save(encounter);
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
