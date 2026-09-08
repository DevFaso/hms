package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.SensitivityCategory;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.recordaccess.SensitivityTagResponseDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.EncounterRepository;
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

    private final EncounterRepository encounterRepository;
    private final DepartmentRepository departmentRepository;
    private final SensitivityClassifier classifier;
    private final AuditEventLogService auditEventLogService;

    public SensitivityTaggingServiceImpl(EncounterRepository encounterRepository,
                                         DepartmentRepository departmentRepository,
                                         SensitivityClassifier classifier,
                                         AuditEventLogService auditEventLogService) {
        this.encounterRepository = encounterRepository;
        this.departmentRepository = departmentRepository;
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

    @Override
    @Transactional(readOnly = true)
    public SensitivityTagResponseDTO getDepartmentDefault(UUID departmentId) {
        Department d = requireDepartment(departmentId);
        return new SensitivityTagResponseDTO(d.getId(), d.getDefaultSensitivityCategory(),
            d.getDefaultSensitivityCategory(), d.getDefaultSensitivityCategory(),
            classifier.travelsCrossHospital(d.getDefaultSensitivityCategory()));
    }

    @Override
    @Transactional
    public SensitivityTagResponseDTO setDepartmentDefault(UUID departmentId, SensitivityCategory category,
                                                          UUID actorUserId) {
        Department department = requireDepartment(departmentId);
        SensitivityCategory before = department.getDefaultSensitivityCategory();
        if (before == category) {
            return getDepartmentDefault(departmentId);
        }
        department.setDefaultSensitivityCategory(category);
        departmentRepository.save(department);
        // A department default silently re-classifies every untagged row
        // recorded there, past and future — that is the point of it, and the
        // reason it is audited as a configuration change rather than a row edit.
        audit(AuditEventType.CONFIGURATION_CHANGED, "DEPARTMENT_DEFAULT_SENSITIVITY",
            departmentId, actorUserId, null, before, category);
        log.info("[sensitivity] department {} default {} -> {}", departmentId, before, category);
        return new SensitivityTagResponseDTO(departmentId, category, category, category,
            classifier.travelsCrossHospital(category));
    }

    private SensitivityTagResponseDTO describe(Encounter encounter) {
        SensitivityCategory effective = classifier.effectiveCategory(encounter);
        SensitivityCategory deptDefault = encounter.getDepartment() == null
            ? null : encounter.getDepartment().getDefaultSensitivityCategory();
        return new SensitivityTagResponseDTO(encounter.getId(), encounter.getSensitivityCategory(),
            effective, deptDefault, classifier.travelsCrossHospital(effective));
    }

    private Encounter requireEncounter(UUID id) {
        return encounterRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_ENCOUNTER_NOT_FOUND, id));
    }

    private Department requireDepartment(UUID id) {
        return departmentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_DEPARTMENT_NOT_FOUND, id));
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
