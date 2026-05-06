package com.example.hms.service;

import com.example.hms.mapper.AdmissionMapper;
import com.example.hms.mapper.LabOrderMapper;
import com.example.hms.mapper.StaffAvailabilityMapper;
import com.example.hms.payload.dto.AdmissionResponseDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabTestDefinitionResponseDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.RecentActivityDTO;
import com.example.hms.payload.dto.StaffAvailabilityResponseDTO;
import com.example.hms.payload.dto.SuperAdminSummaryDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.GeneralReferralRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffAvailabilityRepository;
import com.example.hms.repository.TreatmentPlanRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SuperAdminDashboardServiceImpl implements SuperAdminDashboardService {

    private final UserRepository userRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientRepository patientRepository;
    private final RoleRepository roleRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final AuditEventLogRepository auditEventLogRepository;
    private final OrganizationRepository organizationRepository;
    private final DepartmentRepository departmentRepository;
    private final AppointmentRepository appointmentRepository;
    private final EncounterService encounterService;
    private final StaffAvailabilityRepository staffAvailabilityRepository;
    private final StaffAvailabilityMapper staffAvailabilityMapper;
    private final PatientConsentService patientConsentService;
    private final EncounterRepository encounterRepository;
    private final ConsultationRepository consultationRepository;
    private final LabOrderRepository labOrderRepository;
    private final LabResultRepository labResultRepository;
    private final LabTestDefinitionRepository labTestDefinitionRepository;
    private final AdmissionRepository admissionRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final TreatmentPlanRepository treatmentPlanRepository;
    private final GeneralReferralRepository generalReferralRepository;
    private final ConsultationService consultationService;
    private final LabResultService labResultService;
    private final LabTestDefinitionService labTestDefinitionService;
    private final PrescriptionService prescriptionService;
    private final TreatmentPlanService treatmentPlanService;
    private final GeneralReferralService generalReferralService;
    private final LabOrderMapper labOrderMapper;
    private final AdmissionMapper admissionMapper;

    /**
     * Self-reference for the Spring AOP proxy. Used by
     * {@link #getRecentActivity(int, Locale)} to call the nine per-feed
     * {@code getRecent*} methods <i>through</i> the proxy so each one's
     * {@code @Transactional(readOnly = true)} actually applies.
     *
     * <p>Why this is needed: Spring's transaction management is
     * proxy-based — calling {@code this.getRecentEncounters(...)} bypasses
     * the proxy and silently drops the inner method's transactional
     * configuration. Sonar S6809 / S6809-fr flags this exact pattern.
     * In our case the outer method is also {@code @Transactional} so the
     * functional behaviour is correct (REQUIRED propagation joins the
     * outer tx anyway), but the inner annotations would become
     * load-bearing the day someone removes the outer one — better to
     * keep them honest now.</p>
     *
     * <p>{@code @Lazy} breaks the otherwise-circular constructor
     * dependency Spring would detect (this bean depending on itself).
     * Setter injection (rather than adding to {@code @RequiredArgsConstructor})
     * keeps the existing 30-arg constructor untouched.</p>
     */
    private SuperAdminDashboardService self;

    @Autowired
    public void setSelf(@Lazy SuperAdminDashboardService self) {
        this.self = self;
    }

    @Override
    @Transactional(readOnly = true)
    public SuperAdminSummaryDTO getSummary(int recentAuditLimit) {
        if (recentAuditLimit <= 0 || recentAuditLimit > 50) {
            recentAuditLimit = 10; // sane default + safety cap
        }

        long totalUsers = userRepository.countByIsDeletedFalse();
        long activeUsers = userRepository.countByIsActiveTrueAndIsDeletedFalse();
        long inactiveUsers = Math.max(totalUsers - activeUsers, 0);

        long totalHospitals = hospitalRepository.count();
        long activeHospitals = hospitalRepository.countByActiveTrue();
        long inactiveHospitals = Math.max(totalHospitals - activeHospitals, 0);

        long totalOrganizations = organizationRepository.count();
        long activeOrganizations = organizationRepository.countByActiveTrue();

        long totalDepartments = departmentRepository.count();

        long totalPatients = patientRepository.count();
        long totalRoles = roleRepository.count();
        long totalAssignments = assignmentRepository.count();
        long activeAssignments = assignmentRepository.countByActiveTrue();
        long inactiveAssignments = Math.max(totalAssignments - activeAssignments, 0);
        long globalAssignments = assignmentRepository.countByHospitalIsNull();
        long activeGlobalAssignments = assignmentRepository.countByHospitalIsNullAndActiveTrue();

        // Today's appointments count (system-wide) — DB-level COUNT, no entity loading
        long todayAppointmentsCount = appointmentRepository
                .countByAppointmentDateBetween(LocalDate.now(), LocalDate.now());

        long totalEncounters = encounterRepository.count();
        long totalConsultations = consultationRepository.count();
        long totalLabOrders = labOrderRepository.count();
        long totalLabResults = labResultRepository.count();
        long totalLabTestDefinitions = labTestDefinitionRepository.count();
        long totalAdmissions = admissionRepository.count();
        long totalPrescriptions = prescriptionRepository.count();
        long totalTreatmentPlans = treatmentPlanRepository.count();
        long totalReferrals = generalReferralRepository.count();

        // Fetch recent audit events (simple page order by createdAt / eventTimestamp desc) if repository has method
    var page = auditEventLogRepository.findAllByOrderByEventTimestampDesc(PageRequest.of(0, recentAuditLimit));
        var recent = page.getContent().stream()
            .map(a -> SuperAdminSummaryDTO.RecentAuditEventDTO.builder()
                .id(a.getId().toString())
                .eventType(a.getEventType() != null ? a.getEventType().name() : null)
                .status(a.getStatus() != null ? a.getStatus().name() : null)
                .entityType(a.getEntityType())
                .resourceId(a.getResourceId())
                .resourceName(a.getResourceName())
                .userName(a.getUserName())
                .roleName(a.getRoleName())
                .hospitalName(a.getHospitalName())
                .eventTimestamp(a.getEventTimestamp())
                .eventDescription(a.getEventDescription())
                .build())
            .toList();

        return SuperAdminSummaryDTO.builder()
            .totalUsers(totalUsers)
            .activeUsers(activeUsers)
            .inactiveUsers(inactiveUsers)
            .totalHospitals(totalHospitals)
            .activeHospitals(activeHospitals)
            .inactiveHospitals(inactiveHospitals)
            .totalOrganizations(totalOrganizations)
            .activeOrganizations(activeOrganizations)
            .totalDepartments(totalDepartments)
            .totalPatients(totalPatients)
            .totalRoles(totalRoles)
            .totalAssignments(totalAssignments)
            .activeAssignments(activeAssignments)
            .inactiveAssignments(inactiveAssignments)
            .globalAssignments(globalAssignments)
            .activeGlobalAssignments(activeGlobalAssignments)
            .todayAppointmentsCount(todayAppointmentsCount)
            .totalEncounters(totalEncounters)
            .totalConsultations(totalConsultations)
            .totalLabOrders(totalLabOrders)
            .totalLabResults(totalLabResults)
            .totalLabTestDefinitions(totalLabTestDefinitions)
            .totalAdmissions(totalAdmissions)
            .totalPrescriptions(totalPrescriptions)
            .totalTreatmentPlans(totalTreatmentPlans)
            .totalReferrals(totalReferrals)
            .recentAuditEvents(recent)
            .generatedAt(LocalDateTime.now())
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EncounterResponseDTO> getRecentEncounters(int limit, Locale locale) {
        int safeLimit = sanitizeLimit(limit, 20, 100);
        // Sort by clinical encounter time (`encounterDate`), not row-creation
        // time. A backfilled or migrated encounter would otherwise appear as
        // the "newest activity" even though the visit happened weeks ago.
        // `createdAt` breaks ties (and covers rows where encounterDate is
        // null, which can happen for very early drafts).
        var pageable = PageRequest.of(0, safeLimit,
            Sort.by(Sort.Order.desc("encounterDate"), Sort.Order.desc("createdAt")));
        return encounterService
            .list(null, null, null, null, null, null, pageable, locale)
            .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffAvailabilityResponseDTO> getRecentStaffAvailability(int limit) {
        int safeLimit = sanitizeLimit(limit, 20, 100);
        var pageable = PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.DESC, "date"));
        return staffAvailabilityRepository
            .findAllByOrderByDateDesc(pageable)
            .map(staffAvailabilityMapper::toDto)
            .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientConsentResponseDTO> getRecentPatientConsents(int limit) {
        int safeLimit = sanitizeLimit(limit, 20, 100);
        var pageable = PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.DESC, "consentTimestamp"));
        return patientConsentService.getAllConsents(pageable).getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsultationResponseDTO> getRecentConsultations(int limit) {
        int safeLimit = sanitizeLimit(limit, 20, 100);
        var pageable = PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.DESC, "requestedAt"));
        return consultationService.getRecentForSuperAdmin(pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabOrderResponseDTO> getRecentLabOrders(int limit, Locale locale) {
        int safeLimit = sanitizeLimit(limit, 20, 100);
        // Sort by `orderDatetime` (the clinical order time) to match the
        // existing lab-order list path. Falls back to `createdAt` for
        // null-safety and stable ordering across same-second orders.
        var pageable = PageRequest.of(0, safeLimit,
            Sort.by(Sort.Order.desc("orderDatetime"), Sort.Order.desc("createdAt")));
        return labOrderRepository.findAll(pageable)
            .map(labOrderMapper::toLabOrderResponseDTO)
            .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultResponseDTO> getRecentLabResults(int limit, Locale locale) {
        int safeLimit = sanitizeLimit(limit, 20, 100);
        // Sort by `resultDate` (when the result was produced) so a
        // late-uploaded result doesn't masquerade as the newest activity.
        // `createdAt` breaks ties and covers rows where the lab system
        // never populated resultDate.
        var pageable = PageRequest.of(0, safeLimit,
            Sort.by(Sort.Order.desc("resultDate"), Sort.Order.desc("createdAt")));
        return labResultService.getLabResultsPage(pageable, locale).getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabTestDefinitionResponseDTO> getRecentLabTestDefinitions(int limit) {
        int safeLimit = sanitizeLimit(limit, 20, 100);
        var pageable = PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.DESC, "createdAt"));
        return labTestDefinitionService
            .search(null, null, null, null, null, pageable)
            .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdmissionResponseDTO> getRecentAdmissions(int limit) {
        int safeLimit = sanitizeLimit(limit, 20, 100);
        // Sort by clinical `admissionDateTime` (when the patient was
        // admitted), not row-creation time. Per Copilot review:
        // late-entered or migrated admissions would otherwise show as
        // the newest. `createdAt` breaks ties.
        var pageable = PageRequest.of(0, safeLimit,
            Sort.by(Sort.Order.desc("admissionDateTime"), Sort.Order.desc("createdAt")));
        return admissionRepository.findAll(pageable)
            .map(admissionMapper::toResponseDTO)
            .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrescriptionResponseDTO> getRecentPrescriptions(int limit, Locale locale) {
        int safeLimit = sanitizeLimit(limit, 20, 100);
        // `createdAt` is intentional here: the prescription entity has no
        // separate "clinical write time" field — `createdAt` IS when the
        // provider authored the prescription. Other timestamps on the
        // entity (`dispatchedAt`, `acknowledgedAt`, `cosignedAt`) are
        // workflow-stage events that are null for most rows.
        var pageable = PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.DESC, "createdAt"));
        return prescriptionService.list(null, null, null, pageable, locale).getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TreatmentPlanResponseDTO> getRecentTreatmentPlans(int limit) {
        int safeLimit = sanitizeLimit(limit, 20, 100);
        // Sort by `timelineStartDate` (clinical plan start) — late-edited
        // or migrated plans would otherwise show as the newest. Plans
        // without a start date yet (drafts) fall back to `createdAt`.
        var pageable = PageRequest.of(0, safeLimit,
            Sort.by(Sort.Order.desc("timelineStartDate"), Sort.Order.desc("createdAt")));
        return treatmentPlanService.listAll(null, pageable).getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<GeneralReferralResponseDTO> getRecentReferrals(int limit) {
        int safeLimit = sanitizeLimit(limit, 20, 100);
        // Sort by `submittedAt` (when the referral was submitted) — DRAFTs
        // have null submittedAt and fall through to `createdAt`, which is
        // the right behaviour: a draft only counts as "activity" once it
        // is submitted.
        var pageable = PageRequest.of(0, safeLimit,
            Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.desc("createdAt")));
        return generalReferralService.getRecentForSuperAdmin(pageable);
    }

    /**
     * Compose all nine per-resource recent feeds into a single payload —
     * F5 from {@code docs/super-admin-cross-tenant-design.md}. Delegates
     * to the existing {@code getRecent*} methods so each feed's sort
     * field, locale handling, and authorisation behaviour stays in
     * exactly one place; this method is just a coordinator.
     *
     * <p>One outer {@code @Transactional(readOnly = true)} wraps all
     * nine reads so they observe a single consistent snapshot. Without
     * this, the nine feeds could each open their own transaction and a
     * concurrent write between them would surface partial state in the
     * dashboard (e.g. "5 encounters" in the counter but only 3 in the
     * recent-encounters tab because a delete landed mid-fan-out).</p>
     *
     * <p>Each per-feed call goes through {@link #self} (the AOP proxy)
     * rather than {@code this} so the inner methods' own
     * {@code @Transactional} annotations stay load-bearing — see the
     * Javadoc on {@code self} above. With REQUIRED propagation each
     * inner call joins the outer transaction, preserving the
     * single-snapshot guarantee.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public RecentActivityDTO getRecentActivity(int limit, Locale locale) {
        return RecentActivityDTO.builder()
            .encounters(self.getRecentEncounters(limit, locale))
            .consultations(self.getRecentConsultations(limit))
            .labOrders(self.getRecentLabOrders(limit, locale))
            .labResults(self.getRecentLabResults(limit, locale))
            .labTestDefinitions(self.getRecentLabTestDefinitions(limit))
            .admissions(self.getRecentAdmissions(limit))
            .prescriptions(self.getRecentPrescriptions(limit, locale))
            .treatmentPlans(self.getRecentTreatmentPlans(limit))
            .referrals(self.getRecentReferrals(limit))
            .build();
    }

    private int sanitizeLimit(int requested, int defaultValue, int maxValue) {
        if (requested <= 0) {
            return defaultValue;
        }
        return Math.min(requested, maxValue);
    }
}
