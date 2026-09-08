package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientRecordSharingOptOut;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.recordaccess.RecordSharingOptOutDTO;
import com.example.hms.repository.PatientRecordSharingOptOutRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.TransactionCallbacks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class RecordSharingOptOutServiceImpl implements RecordSharingOptOutService {

    private static final Logger log = LoggerFactory.getLogger(RecordSharingOptOutServiceImpl.class);
    private static final String MSG_PATIENT_NOT_FOUND = "patient.notfound";

    private final PatientRecordSharingOptOutRepository optOutRepository;
    private final PatientRepository patientRepository;
    private final AuditEventLogService auditEventLogService;
    private final MessageSource messageSource;

    public RecordSharingOptOutServiceImpl(PatientRecordSharingOptOutRepository optOutRepository,
                                          PatientRepository patientRepository,
                                          AuditEventLogService auditEventLogService,
                                          MessageSource messageSource) {
        this.optOutRepository = optOutRepository;
        this.patientRepository = patientRepository;
        this.auditEventLogService = auditEventLogService;
        this.messageSource = messageSource;
    }

    @Override
    @Transactional(readOnly = true)
    public RecordSharingOptOutDTO status(UUID patientId, Locale locale) {
        requirePatient(patientId, locale);
        return optOutRepository.findFirstByPatient_IdAndRevokedAtIsNullOrderByOptedOutAtDesc(patientId)
            .map(row -> toDto(patientId, row))
            .orElseGet(() -> RecordSharingOptOutDTO.none(patientId));
    }

    @Override
    @Transactional
    public RecordSharingOptOutDTO optOut(UUID patientId, String reason, UUID actorUserId, Locale locale) {
        Patient patient = requirePatient(patientId, locale);
        if (optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patientId)) {
            throw new ConflictException(messageSource.getMessage(
                "recordaccess.optout.already", new Object[]{patientId}, locale));
        }
        PatientRecordSharingOptOut row = PatientRecordSharingOptOut.builder()
            .patient(patient)
            .optedOutAt(LocalDateTime.now())
            .reason(reason)
            .recordedByUserId(actorUserId)
            .build();
        row = optOutRepository.save(row);
        audit(patientId, actorUserId, "Patient opted out of cross-hospital record sharing");
        log.info("[record-access] opt-out recorded for patient {}", patientId);
        return toDto(patientId, row);
    }

    @Override
    @Transactional
    public RecordSharingOptOutDTO revoke(UUID patientId, UUID actorUserId, Locale locale) {
        requirePatient(patientId, locale);
        PatientRecordSharingOptOut row = optOutRepository
            .findFirstByPatient_IdAndRevokedAtIsNullOrderByOptedOutAtDesc(patientId)
            .orElseThrow(() -> new ConflictException(messageSource.getMessage(
                "recordaccess.optout.none", new Object[]{patientId}, locale)));
        row.setRevokedAt(LocalDateTime.now());
        row.setRevokedByUserId(actorUserId);
        row = optOutRepository.save(row);
        audit(patientId, actorUserId, "Patient's cross-hospital record sharing opt-out revoked");
        log.info("[record-access] opt-out revoked for patient {}", patientId);
        return toDto(patientId, row);
    }

    private Patient requirePatient(UUID patientId, Locale locale) {
        return Optional.ofNullable(patientId)
            .flatMap(patientRepository::findByIdUnscoped)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND, patientId));
    }

    /**
     * After commit, so a rolled-back opt-out never leaves an audit row claiming
     * it happened; REQUIRES_NEW inside the audit service keeps a failed audit
     * from undoing the opt-out.
     */
    private void audit(UUID patientId, UUID actorUserId, String description) {
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.CONSENT_UPDATE)
                    .status(AuditStatus.SUCCESS)
                    .userId(actorUserId)
                    .patientId(patientId)
                    .entityType("PATIENT_RECORD_SHARING_OPTOUT")
                    .resourceId(patientId.toString())
                    .eventDescription(description)
                    .details(Map.of("patientId", patientId.toString()))
                    .build());
            } catch (RuntimeException ex) {
                log.warn("[record-access] audit emission failed for patient {}: {}", patientId, ex.getMessage());
            }
        });
    }

    private static RecordSharingOptOutDTO toDto(UUID patientId, PatientRecordSharingOptOut row) {
        return new RecordSharingOptOutDTO(patientId, row.isInForce(), row.getOptedOutAt(),
            row.getReason(), row.getRevokedAt());
    }
}
