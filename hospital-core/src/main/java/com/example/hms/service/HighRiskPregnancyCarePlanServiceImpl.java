package com.example.hms.service;

import com.example.hms.enums.HighRiskMilestoneType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.HighRiskPregnancyCarePlanMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.highrisk.HighRiskBloodPressureLog;
import com.example.hms.model.highrisk.HighRiskCareTeamNote;
import com.example.hms.model.highrisk.HighRiskMedicationLog;
import com.example.hms.model.highrisk.HighRiskMonitoringMilestone;
import com.example.hms.model.highrisk.HighRiskPregnancyCarePlan;
import com.example.hms.payload.dto.highrisk.HighRiskBloodPressureLogRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskCareTeamNoteRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskMedicationLogRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskPregnancyCarePlanRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskPregnancyCarePlanResponseDTO;
import com.example.hms.repository.HighRiskPregnancyCarePlanRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import java.util.Set;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;

@Service
@RequiredArgsConstructor
@Transactional
public class HighRiskPregnancyCarePlanServiceImpl implements HighRiskPregnancyCarePlanService {
    private static final String PATIENT_NOT_FOUND_KEY = "patient.notFound";
    private static final String CARE_PLAN_NOT_FOUND_KEY = "highRiskPregnancy.carePlan.notFound";


    private static final Logger log = LoggerFactory.getLogger(HighRiskPregnancyCarePlanServiceImpl.class);

    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String ROLE_NURSE = "ROLE_NURSE";
    private static final String ROLE_MIDWIFE = "ROLE_MIDWIFE";
    private static final String ROLE_PATIENT = "ROLE_PATIENT";

    private static final String MSG_PLAN_ID_REQUIRED = "Plan ID is required";
    private static final String MSG_PATIENT_ID_REQUIRED = "Patient ID is required";
    private static final String MSG_LOG_ACCESS_DENIED = "You do not have permission to update monitoring logs";
    private static final String MSG_PLAN_ACCESS_DENIED = "You do not have permission to access this care plan";
    private static final String MSG_PATIENT_ACCESS_DENIED = "You do not have permission to access this patient";

    private static final EnumSet<HighRiskMilestoneType> REQUIRED_BASELINE_MILESTONES = EnumSet.of(
        HighRiskMilestoneType.SPECIALIST_CONSULT,
        HighRiskMilestoneType.HOME_MONITORING_REVIEW,
        HighRiskMilestoneType.EDUCATION_TOUCHPOINT
    );

    private final HighRiskPregnancyCarePlanRepository carePlanRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;
    private final HighRiskPregnancyCarePlanMapper mapper;
    private final Clock clock;
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;
    private final com.example.hms.utility.RoleValidator roleValidator;

    @Override
    public HighRiskPregnancyCarePlanResponseDTO createPlan(HighRiskPregnancyCarePlanRequestDTO request, String username) {
        Objects.requireNonNull(request, "High-risk pregnancy care plan request is required");
        User user = getUserOrThrow(username);
        assertProviderAccess(user);

        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException(PATIENT_NOT_FOUND_KEY, request.getPatientId()));
        Hospital hospital = hospitalRepository.findClinicalById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", request.getHospitalId()));

        ensurePatientBelongsToHospital(patient, hospital.getId());

        HighRiskPregnancyCarePlan plan = new HighRiskPregnancyCarePlan();
        plan.setPatient(patient);
        plan.setHospital(hospital);
        mapper.updateEntityFromRequest(plan, request, true);
        plan.setMonitoringMilestones(mapper.ensureMilestonesContainTypes(plan.getMonitoringMilestones(),
            REQUIRED_BASELINE_MILESTONES.toArray(HighRiskMilestoneType[]::new)));

        HighRiskPregnancyCarePlan saved = carePlanRepository.save(plan);
        log.info("Created high-risk pregnancy plan {} for patient {}", saved.getId(), patient.getId());
        return mapper.toResponse(saved, computeAlerts(saved));
    }

    @Override
    public HighRiskPregnancyCarePlanResponseDTO updatePlan(UUID planId, HighRiskPregnancyCarePlanRequestDTO request, String username) {
    Objects.requireNonNull(planId, MSG_PLAN_ID_REQUIRED);
        Objects.requireNonNull(request, "Update request is required");
        User user = getUserOrThrow(username);
        assertProviderAccess(user);

        HighRiskPregnancyCarePlan plan = findPlanInActingHospital(planId);
        ensurePatientBelongsToHospital(plan.getPatient(), plan.getHospital().getId());

        mapper.updateEntityFromRequest(plan, request, false);
        plan.setMonitoringMilestones(mapper.ensureMilestonesContainTypes(plan.getMonitoringMilestones(),
            REQUIRED_BASELINE_MILESTONES.toArray(HighRiskMilestoneType[]::new)));

        HighRiskPregnancyCarePlan saved = carePlanRepository.save(plan);
        return mapper.toResponse(saved, computeAlerts(saved));
    }

    @Override
    @Transactional(readOnly = true)
    public HighRiskPregnancyCarePlanResponseDTO getPlan(UUID planId, String username) {
        Objects.requireNonNull(planId, MSG_PLAN_ID_REQUIRED);
        User user = getUserOrThrow(username);
        HighRiskPregnancyCarePlan plan = findPlanInReach(user, planId, MSG_PLAN_ACCESS_DENIED);
        return mapper.toResponse(plan, computeAlerts(plan));
    }

    @Override
    @Transactional(readOnly = true)
    public List<HighRiskPregnancyCarePlanResponseDTO> getPlansForPatient(UUID patientId, String username) {
        Objects.requireNonNull(patientId, MSG_PATIENT_ID_REQUIRED);
        User user = getUserOrThrow(username);
        requirePatientInReach(user, patientId);

        // E9 #59d — high-risk care plans follow the patient across the
        // readable hospitals when the caller acts in one.
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        UUID actingHospitalId = ctx.pinnedHospitalId();
        if (actingHospitalId == null) {
            return carePlanRepository.findByPatient_IdOrderByCreatedAtDesc(patientId).stream()
                .map(plan -> mapper.toResponse(plan, computeAlerts(plan)))
                .toList();
        }
        UUID requesterUserId = ctx.getPrincipalUserId();
        Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, actingHospitalId);
        List<HighRiskPregnancyCarePlan> plans = carePlanRepository.findByPatient_IdAndHospital_IdInOrderByCreatedAtDesc(patientId, readable);
        reachRecorder.recordReach(patientId, actingHospitalId, requesterUserId, null,
            CrossHospitalReachRecorder.reachOf(plans.stream().map(r -> CrossHospitalReachRecorder.hospitalIdOf(r.getHospital())).toList(), actingHospitalId),
            "Cross-hospital high-risk pregnancy care plan read on the treatment relationship");
        return plans.stream()
            .map(plan -> mapper.toResponse(plan, computeAlerts(plan)))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public HighRiskPregnancyCarePlanResponseDTO getActivePlan(UUID patientId, String username) {
        Objects.requireNonNull(patientId, MSG_PATIENT_ID_REQUIRED);
        User user = getUserOrThrow(username);
        requirePatientInReach(user, patientId);

        // E9 #59d — the active plan across the readable hospitals.
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        UUID actingHospitalId = ctx.pinnedHospitalId();
        if (actingHospitalId == null) {
            return carePlanRepository.findFirstByPatient_IdAndActiveTrueOrderByCreatedAtDesc(patientId)
                .map(plan -> mapper.toResponse(plan, computeAlerts(plan)))
                .orElse(null);
        }
        UUID requesterUserId = ctx.getPrincipalUserId();
        Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, actingHospitalId);
        List<HighRiskPregnancyCarePlan> active = carePlanRepository.findFirstByPatient_IdAndHospital_IdInAndActiveTrueOrderByCreatedAtDesc(patientId, readable).stream().toList();
        reachRecorder.recordReach(patientId, actingHospitalId, requesterUserId, null,
            CrossHospitalReachRecorder.reachOf(active.stream().map(r -> CrossHospitalReachRecorder.hospitalIdOf(r.getHospital())).toList(), actingHospitalId),
            "Cross-hospital high-risk pregnancy care plan read on the treatment relationship");
        return active.stream()
            .map(plan -> mapper.toResponse(plan, computeAlerts(plan)))
            .findFirst()
            .orElse(null);
    }

    @Override
    public HighRiskPregnancyCarePlanResponseDTO addBloodPressureLog(UUID planId, HighRiskBloodPressureLogRequestDTO request, String username) {
        Objects.requireNonNull(planId, MSG_PLAN_ID_REQUIRED);
        Objects.requireNonNull(request, "Blood pressure log request is required");
        User user = getUserOrThrow(username);

        HighRiskPregnancyCarePlan plan = findPlanInReach(user, planId, MSG_LOG_ACCESS_DENIED);

        HighRiskBloodPressureLog logEntry = mapper.toEntityBloodPressureLog(request);
        List<HighRiskBloodPressureLog> logs = new ArrayList<>(plan.getBloodPressureLogs());
        logs.add(logEntry);
        logs.sort(Comparator.comparing(
            HighRiskBloodPressureLog::getReadingDate,
            Comparator.nullsLast(Comparator.naturalOrder())
        ).reversed());
        plan.setBloodPressureLogs(logs);
        plan.setUpdatedAt(LocalDateTime.now(clock));

        HighRiskPregnancyCarePlan saved = carePlanRepository.save(plan);
        return mapper.toResponse(saved, computeAlerts(saved));
    }

    @Override
    public HighRiskPregnancyCarePlanResponseDTO addMedicationLog(UUID planId, HighRiskMedicationLogRequestDTO request, String username) {
        Objects.requireNonNull(planId, MSG_PLAN_ID_REQUIRED);
        Objects.requireNonNull(request, "Medication log request is required");
        User user = getUserOrThrow(username);

        HighRiskPregnancyCarePlan plan = findPlanInReach(user, planId, MSG_LOG_ACCESS_DENIED);

        HighRiskMedicationLog logEntry = mapper.toEntityMedicationLog(request);
        List<HighRiskMedicationLog> logs = new ArrayList<>(plan.getMedicationLogs());
        logs.add(logEntry);
        logs.sort(Comparator.comparing(
            HighRiskMedicationLog::getTakenAt,
            Comparator.nullsLast(Comparator.naturalOrder())
        ).reversed());
        plan.setMedicationLogs(logs);
        plan.setUpdatedAt(LocalDateTime.now(clock));

        HighRiskPregnancyCarePlan saved = carePlanRepository.save(plan);
        return mapper.toResponse(saved, computeAlerts(saved));
    }

    @Override
    public HighRiskPregnancyCarePlanResponseDTO addCareTeamNote(UUID planId, HighRiskCareTeamNoteRequestDTO request, String username) {
        Objects.requireNonNull(planId, MSG_PLAN_ID_REQUIRED);
        Objects.requireNonNull(request, "Care team note request is required");
        User user = getUserOrThrow(username);
        assertProviderOrPatient(user);

        HighRiskPregnancyCarePlan plan = findPlanInReach(user, planId, MSG_PLAN_ACCESS_DENIED);

        HighRiskCareTeamNote note = mapper.toEntityNote(request);
        List<HighRiskCareTeamNote> notes = new ArrayList<>(plan.getCareTeamNotes());
        notes.add(note);
        notes.sort(Comparator.comparing(
            HighRiskCareTeamNote::getLoggedAt,
            Comparator.nullsLast(Comparator.naturalOrder())
        ).reversed());
        plan.setCareTeamNotes(notes);
        plan.setUpdatedAt(LocalDateTime.now(clock));

        HighRiskPregnancyCarePlan saved = carePlanRepository.save(plan);
        return mapper.toResponse(saved, computeAlerts(saved));
    }

    @Override
    public HighRiskPregnancyCarePlanResponseDTO markMilestoneComplete(UUID planId, UUID milestoneId, LocalDate completionDate, String username) {
        Objects.requireNonNull(planId, MSG_PLAN_ID_REQUIRED);
        Objects.requireNonNull(milestoneId, "Milestone ID is required");
        User user = getUserOrThrow(username);
        assertProviderAccess(user);

        HighRiskPregnancyCarePlan plan = findPlanInActingHospital(planId);
        HighRiskMonitoringMilestone milestone = plan.getMonitoringMilestones().stream()
            .filter(item -> item.getMilestoneId().equals(milestoneId))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("highRiskPregnancy.milestone.notFound", milestoneId));

        milestone.setCompleted(Boolean.TRUE);
        milestone.setCompletedAt(completionDate != null ? completionDate : LocalDate.now(clock));
        plan.setMonitoringMilestones(mapper.ensureMilestonesContainTypes(plan.getMonitoringMilestones(),
            REQUIRED_BASELINE_MILESTONES.toArray(HighRiskMilestoneType[]::new)));
        plan.getMonitoringMilestones().sort(this::compareMilestones);
        plan.setUpdatedAt(LocalDateTime.now(clock));

        HighRiskPregnancyCarePlan saved = carePlanRepository.save(plan);
        return mapper.toResponse(saved, computeAlerts(saved));
    }

    private List<String> computeAlerts(HighRiskPregnancyCarePlan plan) {
        List<String> alerts = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);

        LocalDate lastReading = plan.resolveLatestBloodPressureLogDate();
        if (lastReading == null) {
            alerts.add("No blood pressure readings logged yet");
        } else if (lastReading.isBefore(today.minusDays(7))) {
            alerts.add("Blood pressure has not been logged in the last 7 days");
        }

        boolean overdueMilestone = plan.getMonitoringMilestones().stream()
            .filter(item -> Boolean.FALSE.equals(item.getCompleted()))
            .anyMatch(item -> item.getTargetDate() != null && item.getTargetDate().isBefore(today));
        if (overdueMilestone) {
            alerts.add("One or more monitoring milestones are overdue");
        }

        if (plan.getRiskLevel() != null && plan.getRiskLevel().equalsIgnoreCase("critical")) {
            alerts.add("Patient flagged as critical risk level");
        }

        if (Boolean.FALSE.equals(plan.getActive())) {
            alerts.add("Care plan is inactive");
        }

        return alerts;
    }

    private int compareMilestones(HighRiskMonitoringMilestone a, HighRiskMonitoringMilestone b) {
        LocalDate aDate = a.getTargetDate();
        LocalDate bDate = b.getTargetDate();
        if (aDate == null && bDate == null) {
            return a.getMilestoneId().compareTo(b.getMilestoneId());
        }
        if (aDate == null) {
            return 1;
        }
        if (bDate == null) {
            return -1;
        }
        return aDate.compareTo(bDate);
    }

    /**
     * A provider reaches a plan only at the hospital they act at, as every
     * other clinical write and by-id read does; another hospital's plan
     * answers exactly as a missing one. A null scope (a super-admin in global
     * view) reaches any. Not asked of a patient, whose boundary is ownership.
     */
    private HighRiskPregnancyCarePlan findPlanInActingHospital(UUID planId) {
        HighRiskPregnancyCarePlan plan = findPlanOrThrow(planId);
        UUID scope = roleValidator.requireActiveHospitalId();
        if (scope != null && (plan.getHospital() == null || !scope.equals(plan.getHospital().getId()))) {
            throw new ResourceNotFoundException(CARE_PLAN_NOT_FOUND_KEY, planId);
        }
        return plan;
    }

    private HighRiskPregnancyCarePlan findPlanOrThrow(UUID planId) {
        return carePlanRepository.findById(planId)
            .orElseThrow(() -> new ResourceNotFoundException(CARE_PLAN_NOT_FOUND_KEY, planId));
    }

    private User getUserOrThrow(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new ResourceNotFoundException("user.notFoundByUsername", username));
    }

    private void assertProviderAccess(User user) {
        if (!isProvider(user)) {
            throw new BusinessException("Only clinical staff can perform this action");
        }
    }

    private void assertProviderOrPatient(User user) {
        if (isProvider(user) || isPatient(user)) {
            return;
        }
        throw new BusinessException("Action limited to clinical staff or the patient");
    }

    /**
     * The plan, when this caller may reach it. A provider (a super-admin
     * included) reaches any plan, as before. A caller who is neither a provider
     * nor a patient is refused BEFORE the lookup, so the answer cannot depend on
     * the id. A patient reaches only a plan for their own row, and another
     * patient's plan answers exactly as a missing one: it used to be a 400
     * ("permission") for a real id beside a 404 for a made-up one.
     */
    private HighRiskPregnancyCarePlan findPlanInReach(User user, UUID planId, String deniedMessage) {
        boolean provider = isProvider(user);
        if (!provider && !isPatient(user)) {
            throw new BusinessException(deniedMessage);
        }
        HighRiskPregnancyCarePlan plan = provider ? findPlanInActingHospital(planId) : findPlanOrThrow(planId);
        if (!provider && !ownsPatient(user, plan.getPatient() != null ? plan.getPatient().getId() : null)) {
            throw new ResourceNotFoundException(CARE_PLAN_NOT_FOUND_KEY, planId);
        }
        return plan;
    }

    /**
     * The patient-id reads: a provider reads any patient that exists; a patient
     * names only their own row and is told otherwise exactly as for an unknown
     * id, before the patient is looked up; anyone else is refused before it.
     */
    private void requirePatientInReach(User user, UUID patientId) {
        if (isProvider(user)) {
            patientRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException(PATIENT_NOT_FOUND_KEY, patientId));
            return;
        }
        if (!isPatient(user)) {
            throw new BusinessException(MSG_PATIENT_ACCESS_DENIED);
        }
        if (!ownsPatient(user, patientId)) {
            throw new ResourceNotFoundException(PATIENT_NOT_FOUND_KEY, patientId);
        }
    }

    /** {@code existsByIdAndUserId}: no 500 on duplicate user_id rows, no Patient decrypted. */
    private boolean ownsPatient(User user, UUID patientId) {
        return patientId != null && patientRepository.existsByIdAndUserId(patientId, user.getId());
    }

    /** E9 #67 (D5): the platform operator is counted with the providers. */
    private boolean isProvider(User user) {
        return hasRole(user, ROLE_SUPER_ADMIN)
            || hasRole(user, ROLE_DOCTOR)
            || hasRole(user, ROLE_MIDWIFE)
            || hasRole(user, ROLE_NURSE);
    }

    private boolean isPatient(User user) {
        return hasRole(user, ROLE_PATIENT);
    }

    private boolean hasRole(User user, String role) {
        return user.getUserRoles() != null && user.getUserRoles().stream()
            .anyMatch(userRole -> userRole.getRole() != null && role.equals(userRole.getRole().getCode()));
    }

    private void ensurePatientBelongsToHospital(Patient patient, UUID hospitalId) {
        boolean registered = patient.getHospitalRegistrations().stream()
            .filter(Objects::nonNull)
            .anyMatch(reg -> reg.getHospital() != null && hospitalId.equals(reg.getHospital().getId()) && reg.isActive());
        if (!registered) {
            throw new BusinessException("Patient is not registered in the requested hospital");
        }
    }
}
