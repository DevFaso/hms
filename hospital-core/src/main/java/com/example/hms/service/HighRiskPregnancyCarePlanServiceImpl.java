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

@Service
@RequiredArgsConstructor
@Transactional
public class HighRiskPregnancyCarePlanServiceImpl implements HighRiskPregnancyCarePlanService {

    private static final Logger log = LoggerFactory.getLogger(HighRiskPregnancyCarePlanServiceImpl.class);

    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final String ROLE_HOSPITAL_ADMIN = "ROLE_HOSPITAL_ADMIN";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String ROLE_NURSE = "ROLE_NURSE";
    private static final String ROLE_MIDWIFE = "ROLE_MIDWIFE";
    private static final String ROLE_PATIENT = "ROLE_PATIENT";

    private static final String MSG_PATIENT_NOT_FOUND = "Patient not found";
    private static final String MSG_HOSPITAL_NOT_FOUND = "Hospital not found";
    private static final String MSG_PLAN_NOT_FOUND = "High-risk pregnancy care plan not found";
    private static final String MSG_PLAN_ID_REQUIRED = "Plan ID is required";
    private static final String MSG_PATIENT_ID_REQUIRED = "Patient ID is required";
    private static final String MSG_USERNAME_PREFIX = "User not found: ";
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

    @Override
    public HighRiskPregnancyCarePlanResponseDTO createPlan(HighRiskPregnancyCarePlanRequestDTO request, String username) {
        Objects.requireNonNull(request, "High-risk pregnancy care plan request is required");
        User user = getUserOrThrow(username);
        assertProviderAccess(user);

        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND));
        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(MSG_HOSPITAL_NOT_FOUND));

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

        HighRiskPregnancyCarePlan plan = findPlanOrThrow(planId);
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
        HighRiskPregnancyCarePlan plan = findPlanOrThrow(planId);
        assertReadAccess(user, plan);
        return mapper.toResponse(plan, computeAlerts(plan));
    }

    @Override
    @Transactional(readOnly = true)
    public List<HighRiskPregnancyCarePlanResponseDTO> getPlansForPatient(UUID patientId, String username) {
        Objects.requireNonNull(patientId, MSG_PATIENT_ID_REQUIRED);
        User user = getUserOrThrow(username);
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND));
        assertReadAccess(user, patient);

        return carePlanRepository.findByPatient_IdOrderByCreatedAtDesc(patientId).stream()
            .map(plan -> mapper.toResponse(plan, computeAlerts(plan)))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public HighRiskPregnancyCarePlanResponseDTO getActivePlan(UUID patientId, String username) {
        Objects.requireNonNull(patientId, MSG_PATIENT_ID_REQUIRED);
        User user = getUserOrThrow(username);
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND));
        assertReadAccess(user, patient);

        return carePlanRepository.findFirstByPatient_IdAndActiveTrueOrderByCreatedAtDesc(patientId)
            .map(plan -> mapper.toResponse(plan, computeAlerts(plan)))
            .orElse(null);
    }

    @Override
    public HighRiskPregnancyCarePlanResponseDTO addBloodPressureLog(UUID planId, HighRiskBloodPressureLogRequestDTO request, String username) {
        Objects.requireNonNull(planId, MSG_PLAN_ID_REQUIRED);
        Objects.requireNonNull(request, "Blood pressure log request is required");
        User user = getUserOrThrow(username);

        HighRiskPregnancyCarePlan plan = findPlanOrThrow(planId);
        assertLogAccess(user, plan);

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

        HighRiskPregnancyCarePlan plan = findPlanOrThrow(planId);
        assertLogAccess(user, plan);

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

        HighRiskPregnancyCarePlan plan = findPlanOrThrow(planId);
        assertReadAccess(user, plan);

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

        HighRiskPregnancyCarePlan plan = findPlanOrThrow(planId);
        HighRiskMonitoringMilestone milestone = plan.getMonitoringMilestones().stream()
            .filter(item -> item.getMilestoneId().equals(milestoneId))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Milestone not found"));

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

    private HighRiskPregnancyCarePlan findPlanOrThrow(UUID planId) {
        return carePlanRepository.findById(planId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PLAN_NOT_FOUND));
    }

    private User getUserOrThrow(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_USERNAME_PREFIX + username));
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

    private void assertReadAccess(User user, HighRiskPregnancyCarePlan plan) {
        if (isProvider(user) || isHospitalAdmin(user)) {
            return;
        }
        if (isPatient(user)) {
            Patient patient = getPatientByUserOrThrow(user);
            if (patient.getId().equals(plan.getPatient().getId())) {
                return;
            }
        }
    throw new BusinessException(MSG_PLAN_ACCESS_DENIED);
    }

    private void assertReadAccess(User user, Patient patient) {
        if (isProvider(user) || isHospitalAdmin(user)) {
            return;
        }
        if (isPatient(user)) {
            Patient linked = getPatientByUserOrThrow(user);
            if (linked.getId().equals(patient.getId())) {
                return;
            }
        }
    throw new BusinessException(MSG_PATIENT_ACCESS_DENIED);
    }

    private void assertLogAccess(User user, HighRiskPregnancyCarePlan plan) {
        if (isProvider(user)) {
            return;
        }
        if (isPatient(user)) {
            Patient patient = getPatientByUserOrThrow(user);
            if (patient.getId().equals(plan.getPatient().getId())) {
                return;
            }
        }
    throw new BusinessException(MSG_LOG_ACCESS_DENIED);
    }

    private boolean isProvider(User user) {
        return hasRole(user, ROLE_SUPER_ADMIN)
            || hasRole(user, ROLE_DOCTOR)
            || hasRole(user, ROLE_MIDWIFE)
            || hasRole(user, ROLE_NURSE);
    }

    private boolean isHospitalAdmin(User user) {
        return hasRole(user, ROLE_HOSPITAL_ADMIN) || hasRole(user, ROLE_SUPER_ADMIN);
    }

    private boolean isPatient(User user) {
        return hasRole(user, ROLE_PATIENT);
    }

    private boolean hasRole(User user, String role) {
        return user.getUserRoles() != null && user.getUserRoles().stream()
            .anyMatch(userRole -> userRole.getRole() != null && role.equals(userRole.getRole().getCode()));
    }

    private Patient getPatientByUserOrThrow(User user) {
        return patientRepository.findByUserId(user.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Patient record not found for user: " + user.getUsername()));
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
