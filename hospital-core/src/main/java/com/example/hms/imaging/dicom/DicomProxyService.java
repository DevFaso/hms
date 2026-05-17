package com.example.hms.imaging.dicom;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.service.AuditEventLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Foundation-pass DICOM proxy service (roadmap row 42, v2.0 /
 * Clinical Depth).
 *
 * <p>Today the imaging module surfaces reports + an external PACS
 * viewer link (V75 {@code pacs_viewer_url_template}). This service
 * is the entry point for HMS-mediated pixel fetches; the foundation
 * pass wires the flag + audit contract so the receptionist /
 * clinician UI can be built against a stable empty-list response,
 * and the row-42 follow-on plugs in the real Orthanc / dcm4chee
 * HTTP client.
 */
@Service
public class DicomProxyService {

    private static final Logger log = LoggerFactory.getLogger(DicomProxyService.class);
    private static final String AUDIT_ENTITY_TYPE = "IMAGING_STUDY";

    private final DicomProxyProperties properties;
    private final AuditEventLogService auditEventLogService;

    public DicomProxyService(
        DicomProxyProperties properties,
        AuditEventLogService auditEventLogService
    ) {
        this.properties = properties;
        this.auditEventLogService = auditEventLogService;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * Resolve the list of instance UIDs for a given study UID via the
     * configured DICOMweb adapter (QIDO-RS).
     *
     * <p>Foundation pass: returns an empty list unconditionally + emits
     * the {@code IMAGING_RESULT_UPDATED} audit event so the audit trail
     * accumulates real-world usage data even before the HTTP client
     * lands. The row-42 follow-on adds the actual upstream call.
     */
    public List<String> listInstancesForStudy(String studyUid) {
        if (!properties.isEnabled()) return Collections.emptyList();
        if (studyUid == null || studyUid.isBlank()) return Collections.emptyList();
        emitAudit(studyUid,
            "DICOM proxy QIDO-RS lookup for study " + studyUid
                + " (foundation-pass — upstream call deferred)");
        // TODO row-42 follow-on: invoke the configured adapter's
        // DICOMweb QIDO-RS endpoint (Orthanc /dicom-web/studies/{uid}/instances
        // or dcm4chee equivalent), parse the JSON response, return the
        // instance UIDs. Add cross-tenant guard against
        // ImagingOrder.hospital + HospitalContextHolder.getActiveHospitalId().
        return Collections.emptyList();
    }

    private void emitAudit(String studyUid, String description) {
        try {
            AuditEventRequestDTO request = AuditEventRequestDTO.builder()
                .eventType(AuditEventType.IMAGING_RESULT_UPDATED)
                .status(AuditStatus.SUCCESS)
                .entityType(AUDIT_ENTITY_TYPE)
                .resourceId(studyUid)
                .eventDescription(description)
                .build();
            auditEventLogService.logEvent(request);
        } catch (RuntimeException ex) {
            log.warn("audit emission failed for DICOM proxy study {}: {}", studyUid, ex.toString());
        }
    }
}
