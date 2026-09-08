package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.RecordAccessPosture;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.recordaccess.RecordAccessPostureDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.TransactionCallbacks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class HospitalRecordAccessPostureServiceImpl implements HospitalRecordAccessPostureService {

    private static final Logger log = LoggerFactory.getLogger(HospitalRecordAccessPostureServiceImpl.class);
    private static final String MSG_HOSPITAL_NOT_FOUND = "hospital.notfound";

    private final HospitalRepository hospitalRepository;
    private final AuditEventLogService auditEventLogService;

    public HospitalRecordAccessPostureServiceImpl(HospitalRepository hospitalRepository,
                                                  AuditEventLogService auditEventLogService) {
        this.hospitalRepository = hospitalRepository;
        this.auditEventLogService = auditEventLogService;
    }

    @Override
    @Transactional(readOnly = true)
    public RecordAccessPostureDTO get(UUID hospitalId) {
        return toDto(require(hospitalId));
    }

    @Override
    @Transactional
    public RecordAccessPostureDTO set(UUID hospitalId, RecordAccessPosture posture, UUID actorUserId) {
        Hospital hospital = require(hospitalId);
        RecordAccessPosture before = hospital.getRecordAccessPosture();
        if (before == posture) {
            return toDto(hospital);
        }
        hospital.setRecordAccessPosture(posture);
        hospital = hospitalRepository.save(hospital);
        String description = "Record-access posture changed from " + before + " to " + posture;
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.CONFIGURATION_CHANGED)
                    .status(AuditStatus.SUCCESS)
                    .userId(actorUserId)
                    .entityType("HOSPITAL_RECORD_ACCESS_POSTURE")
                    .resourceId(hospitalId.toString())
                    .eventDescription(description)
                    .details(Map.of("hospitalId", hospitalId.toString(),
                        "from", String.valueOf(before), "to", posture.name()))
                    .build());
            } catch (RuntimeException ex) {
                log.warn("[record-access] posture audit failed for hospital {}: {}", hospitalId, ex.getMessage());
            }
        });
        log.info("[record-access] hospital {} posture {} -> {}", hospitalId, before, posture);
        return toDto(hospital);
    }

    private Hospital require(UUID hospitalId) {
        return hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_HOSPITAL_NOT_FOUND, hospitalId));
    }

    private static RecordAccessPostureDTO toDto(Hospital h) {
        return new RecordAccessPostureDTO(h.getId(), h.getRecordAccessPosture(), h.getIsolationMode());
    }
}
