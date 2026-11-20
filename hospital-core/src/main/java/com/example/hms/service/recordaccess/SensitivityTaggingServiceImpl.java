package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.SensitivityCategory;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.PatientProblem;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.recordaccess.SensitivityTagResponseDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.TransactionCallbacks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class SensitivityTaggingServiceImpl implements SensitivityTaggingService {

    private static final Logger log = LoggerFactory.getLogger(SensitivityTaggingServiceImpl.class);
    private static final String MSG_ENCOUNTER_NOT_FOUND = "encounter.notfound";
    private static final String MSG_DEPARTMENT_NOT_FOUND = "department.notfound";
    private static final String MSG_PROBLEM_NOT_FOUND = "diagnosis.notFound";

    private final EncounterRepository encounterRepository;
    private final DepartmentRepository departmentRepository;
    private final PatientProblemRepository patientProblemRepository;
    private final SensitivityClassifier classifier;
    private final AuditEventLogService auditEventLogService;

    public SensitivityTaggingServiceImpl(EncounterRepository encounterRepository,
                                         DepartmentRepository departmentRepository,
                                         PatientProblemRepository patientProblemRepository,
                                         SensitivityClassifier classifier,
                                         AuditEventLogService auditEventLogService) {
        this.encounterRepository = encounterRepository;
        this.departmentRepository = departmentRepository;
        this.patientProblemRepository = patientProblemRepository;
        this.classifier = classifier;
        this.auditEventLogService = auditEventLogService;
    }

    @Override
    @Transactional(readOnly = true)
    public SensitivityTagResponseDTO getEncounterTag(UUID encounterId) {
        return describe(requireEncounter(encounterId));
    }

    @Override
    @Transactional
    public SensitivityTagResponseDTO tagEncounter(UUID encounterId, SensitivityCategory category, UUID actorUserId) {
        Encounter encounter = requireEncounter(encounterId);
        SensitivityCategory before = encounter.getSensitivityCategory();
        if (before == category) {
            return describe(encounter);
        }
        encounter.setSensitivityCategory(category);
        encounter = encounterRepository.save(encounter);
        audit(AuditEventType.DATA_UPDATE, "ENCOUNTER_SENSITIVITY", encounterId, actorUserId,
            encounter.getPatient() == null ? null : encounter.getPatient().getId(), before, category);
        log.info("[sensitivity] encounter {} tag {} -> {}", encounterId, before, category);
        return describe(encounter);
    }

    /**
     * QA D2730 (r65, High): nothing wrote a problem's tag, so the proxy filter
     * that read it withheld no diagnosis. A clinician tags the entry here; a
     * problem recorded in a tagged visit takes that visit's category without
     * anyone doing so.
     */
    @Override
    @Transactional
    public SensitivityTagResponseDTO tagProblem(UUID patientId, UUID problemId, SensitivityCategory category,
                                                UUID actorUserId) {
        PatientProblem problem = requireProblem(patientId, problemId);
        SensitivityCategory before = problem.getSensitivityCategory();
        if (before == category) {
            return describe(problem);
        }
        problem.setSensitivityCategory(category);
        problem = patientProblemRepository.save(problem);
        audit(AuditEventType.DATA_UPDATE, "PROBLEM_SENSITIVITY", problemId, actorUserId, patientId, before, category);
        log.info("[sensitivity] problem {} tag {} -> {}", problemId, before, category);
        return describe(problem);
    }

    @Override
    @Transactional(readOnly = true)
    public SensitivityTagResponseDTO getDepartmentDefault(UUID departmentId) {
        return describeDepartment(requireDepartment(departmentId));
    }

    @Override
    @Transactional
    public SensitivityTagResponseDTO setDepartmentDefault(UUID departmentId, SensitivityCategory category,
                                                          UUID actorUserId) {
        Department department = requireDepartment(departmentId);
        SensitivityCategory before = department.getDefaultSensitivityCategory();
        if (before == category) {
            return describeDepartment(department);
        }
        department.setDefaultSensitivityCategory(category);
        departmentRepository.save(department);
        // A department default silently re-classifies every untagged row
        // recorded there, past and future — that is the point of it, and the
        // reason it is audited as a configuration change rather than a row edit.
        audit(AuditEventType.CONFIGURATION_CHANGED, "DEPARTMENT_DEFAULT_SENSITIVITY",
            departmentId, actorUserId, null, before, category);
        log.info("[sensitivity] department {} default {} -> {}", departmentId, before, category);
        return describeDepartment(department);
    }

    /**
     * A department's own default IS its effective category — it has no parent
     * to inherit from — so all three fields carry the same value.
     *
     * <p>Takes the loaded entity rather than an id on purpose. The previous
     * shape had {@code setDepartmentDefault} call {@code getDepartmentDefault},
     * which is a {@code @Transactional} self-invocation: the inner
     * {@code readOnly = true} never applied (Spring's proxy is invocation-time,
     * so the call bypassed it), and it re-read a row already in the persistence
     * context. Sonar S2229.
     */
    private SensitivityTagResponseDTO describeDepartment(Department department) {
        SensitivityCategory category = department.getDefaultSensitivityCategory();
        return new SensitivityTagResponseDTO(department.getId(), category, category, category,
            classifier.travelsCrossHospital(category));
    }

    private SensitivityTagResponseDTO describe(Encounter encounter) {
        SensitivityCategory effective = classifier.effectiveCategory(encounter);
        Department department = encounter.getDepartment();
        SensitivityCategory deptDefault = department == null
            ? null : department.getDefaultSensitivityCategory();
        return new SensitivityTagResponseDTO(encounter.getId(), encounter.getSensitivityCategory(),
            effective, deptDefault, classifier.travelsCrossHospital(effective));
    }

    private SensitivityTagResponseDTO describe(PatientProblem problem) {
        SensitivityCategory effective = classifier.effectiveCategory(problem);
        Encounter visit = problem.getSourceEncounter();
        Department department = visit == null ? null : visit.getDepartment();
        SensitivityCategory deptDefault = department == null
            ? null : department.getDefaultSensitivityCategory();
        return new SensitivityTagResponseDTO(problem.getId(), problem.getSensitivityCategory(),
            effective, deptDefault, classifier.travelsCrossHospital(effective));
    }

    /** The problem, when it is this patient's and the acting hospital recorded it; unknown otherwise. */
    private PatientProblem requireProblem(UUID patientId, UUID problemId) {
        PatientProblem problem = patientProblemRepository.findById(problemId)
            .filter(found -> found.getPatient() != null && found.getPatient().getId() != null
                && found.getPatient().getId().equals(patientId))
            // A diagnosis removed as entered in error (QA D1240) is no longer on the list to tag.
            .filter(found -> !found.isEnteredInError())
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PROBLEM_NOT_FOUND, problemId));
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(problem.getHospital(), MSG_PROBLEM_NOT_FOUND, problemId);
        return problem;
    }

    private Encounter requireEncounter(UUID id) {
        Encounter encounter = encounterRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_ENCOUNTER_NOT_FOUND, id));
        // QA r5 tenancy sweep: another hospital's encounter answers as unknown.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(encounter.getHospital(), MSG_ENCOUNTER_NOT_FOUND, id);
        return encounter;
    }

    private Department requireDepartment(UUID id) {
        Department department = departmentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_DEPARTMENT_NOT_FOUND, id));
        // QA r5 tenancy sweep: another hospital's department answers as unknown.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(department.getHospital(), MSG_DEPARTMENT_NOT_FOUND, id);
        return department;
    }

    /**
     * After commit, and carrying category names only — never the clinical text
     * that made the row sensitive.
     */
    private void audit(AuditEventType type, String entityType, UUID resourceId, UUID actorUserId,
                       UUID patientId, SensitivityCategory before, SensitivityCategory after) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", String.valueOf(before));
        details.put("to", String.valueOf(after));
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(type)
                    .status(AuditStatus.SUCCESS)
                    .userId(actorUserId)
                    .patientId(patientId)
                    .entityType(entityType)
                    .resourceId(resourceId.toString())
                    .eventDescription("Sensitivity category changed from " + before + " to " + after)
                    .details(details)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("[sensitivity] audit emission failed for {} {}: {}", entityType, resourceId, ex.getMessage());
            }
        });
    }
}
