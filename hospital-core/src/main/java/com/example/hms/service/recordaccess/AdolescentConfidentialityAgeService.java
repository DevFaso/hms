package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.recordaccess.AdolescentConfidentialityAgeDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Policy 2026-11-20 (provisional): the per-hospital adolescent confidentiality
 * age. Burkina has no statutory age, so the default (12) is borrowed and a
 * jurist must confirm it; a hospital sets its own here, and every change is a
 * {@code CONFIGURATION_CHANGED} audit row.
 */
@Service
@RequiredArgsConstructor
public class AdolescentConfidentialityAgeService {

    private static final Logger log = LoggerFactory.getLogger(AdolescentConfidentialityAgeService.class);
    private static final String MSG_HOSPITAL_NOT_FOUND = "hospital.notfound";

    private final HospitalRepository hospitalRepository;
    private final AuditEventLogService auditEventLogService;

    @Transactional(readOnly = true)
    public AdolescentConfidentialityAgeDTO get(UUID hospitalId) {
        return toDto(require(hospitalId));
    }

    /** {@code age} may be null, which returns the hospital to the provisional default. */
    @Transactional
    public AdolescentConfidentialityAgeDTO set(UUID hospitalId, Integer age, UUID actorUserId) {
        Hospital hospital = require(hospitalId);
        Integer before = hospital.getAdolescentConfidentialityAge();
        if (Objects.equals(before, age)) {
            return toDto(hospital);
        }
        hospital.setAdolescentConfidentialityAge(age);
        hospital = hospitalRepository.save(hospital);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("hospitalId", hospitalId.toString());
        details.put("from", String.valueOf(before));
        details.put("to", String.valueOf(age));
        AuditEventRequestDTO event = AuditEventRequestDTO.builder()
            .eventType(AuditEventType.CONFIGURATION_CHANGED)
            .status(AuditStatus.SUCCESS)
            .userId(actorUserId)
            .hospitalId(hospitalId)
            .entityType("HOSPITAL_ADOLESCENT_CONFIDENTIALITY_AGE")
            .resourceId(hospitalId.toString())
            .eventDescription(age == null
                ? "Adolescent confidentiality age reset to the default ("
                    + ProxySensitivityPolicy.DEFAULT_ADOLESCENT_AGE + ")"
                : "Adolescent confidentiality age set to " + age)
            .details(details)
            .build();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(event);
            } catch (RuntimeException ex) {
                log.warn("[record-access] adolescent-age audit failed for hospital {}: {}", hospitalId,
                    ex.getClass().getSimpleName());
            }
        });
        log.info("[record-access] hospital {} adolescent confidentiality age {} -> {}", hospitalId, before, age);
        return toDto(hospital);
    }

    private Hospital require(UUID hospitalId) {
        return hospitalRepository.findClinicalById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_HOSPITAL_NOT_FOUND, hospitalId));
    }

    private static AdolescentConfidentialityAgeDTO toDto(Hospital hospital) {
        Integer configured = hospital.getAdolescentConfidentialityAge();
        return new AdolescentConfidentialityAgeDTO(hospital.getId(), configured,
            ProxySensitivityPolicy.adolescentAgeOr(configured), ProxySensitivityPolicy.DEFAULT_ADOLESCENT_AGE, true);
    }
}
