package com.example.hms.service.imaging;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingReportingSettingsDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Policy rule 3 (owner 2026-11-20, PROVISIONAL): a hospital's imaging reporting settings (V346).
 *
 * <p>One setting, « Échographie par un non-médecin » (owner decision 2026-11-20): whether an
 * imaging technician may PERFORM an ultrasound at this hospital. It defaults to physicians only,
 * and then a technician is refused the exam's steps and its record on an ultrasound order
 * ({@code ImagingTechnicianScope}). The report stays a physician's whatever is chosen: nothing
 * opens reporting to anyone on it (sonographer reporting is not built). Every change is a
 * {@code CONFIGURATION_CHANGED} audit row.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImagingReportingSettingsService {

    private static final String MSG_HOSPITAL_NOT_FOUND = "hospital.notfound";

    private final HospitalRepository hospitalRepository;
    private final AuditEventLogService auditEventLogService;

    @Transactional(readOnly = true)
    public ImagingReportingSettingsDTO get(UUID hospitalId) {
        return toDto(require(hospitalId));
    }

    @Transactional
    public ImagingReportingSettingsDTO setUltrasoundByNonPhysician(UUID hospitalId, boolean ultrasoundByNonPhysician,
                                                              UUID actorUserId) {
        Hospital hospital = require(hospitalId);
        if (hospital.isUltrasoundByNonPhysician() == ultrasoundByNonPhysician) {
            return toDto(hospital);
        }
        hospital.setUltrasoundByNonPhysician(ultrasoundByNonPhysician);
        hospitalRepository.save(hospital);
        TransactionCallbacks.afterCommit(() -> audit(hospitalId, actorUserId, ultrasoundByNonPhysician));
        return toDto(hospital);
    }

    private void audit(UUID hospitalId, UUID actorUserId, boolean value) {
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .eventType(AuditEventType.CONFIGURATION_CHANGED)
                .status(AuditStatus.SUCCESS)
                .userId(actorUserId)
                .hospitalId(hospitalId)
                .entityType("HOSPITAL_IMAGING_REPORTING_SETTINGS")
                .resourceId(hospitalId.toString())
                .eventDescription("Imaging reporting settings changed")
                .details(Map.of("hospitalId", hospitalId.toString(),
                    "ultrasoundByNonPhysician", String.valueOf(value)))
                .build());
        } catch (RuntimeException ex) {
            log.warn("[imaging] reporting settings audit failed for hospital {}: {}", hospitalId,
                ex.getClass().getSimpleName());
        }
    }

    private Hospital require(UUID hospitalId) {
        return hospitalRepository.findClinicalById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_HOSPITAL_NOT_FOUND, hospitalId));
    }

    private static ImagingReportingSettingsDTO toDto(Hospital hospital) {
        return new ImagingReportingSettingsDTO(hospital.getId(), hospital.isUltrasoundByNonPhysician());
    }
}
