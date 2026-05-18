package com.example.hms.imaging.dicom;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.SecurityUtils;
import com.example.hms.service.AuditEventLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * DICOM proxy service (roadmap row 42, v2.0 / Clinical Depth).
 *
 * <p>Foundation pass shipped the flag + the audit contract; the
 * row-42 follow-on adds the upstream DICOMweb bridge via
 * {@link DicomWebClient}. When the bridge bean is present (flag on +
 * base-url configured) {@link #listInstancesForStudy} delegates the
 * QIDO-RS query and {@link #fetchInstanceBytes} delegates the WADO-RS
 * pixel fetch; when absent, both methods return the foundation-pass
 * empty-list / null while still emitting the audit row.
 */
@Service
public class DicomProxyService {

    private static final Logger log = LoggerFactory.getLogger(DicomProxyService.class);
    private static final String AUDIT_ENTITY_TYPE = "IMAGING_STUDY";

    private final DicomProxyProperties properties;
    private final AuditEventLogService auditEventLogService;
    private final UserRepository userRepository;
    /**
     * Row-42 follow-on: the upstream DICOMweb bridge. Optional —
     * present only when {@code app.imaging.dicom-proxy.enabled=true}
     * AND a {@link DicomWebHttpClient} (or test double) is registered.
     * Absent means the foundation-pass behaviour applies: audit-only,
     * no upstream call.
     */
    private final Optional<DicomWebClient> dicomWebClient;

    public DicomProxyService(
        DicomProxyProperties properties,
        AuditEventLogService auditEventLogService,
        UserRepository userRepository,
        @Autowired(required = false) DicomWebClient dicomWebClient
    ) {
        this.properties = properties;
        this.auditEventLogService = auditEventLogService;
        this.userRepository = userRepository;
        this.dicomWebClient = Optional.ofNullable(dicomWebClient);
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * Resolve the list of instance UIDs for a given study UID via the
     * configured DICOMweb adapter (QIDO-RS). Row-42 follow-on now
     * delegates to {@link DicomWebClient#qidoListInstances} when the
     * upstream bridge bean is present; falls back to the foundation-
     * pass audit-only path when absent.
     *
     * <p>Audit emission stays unconditional on a flag-on call — the
     * IMAGING_RESULT_UPDATED row carries the operator + study even
     * when the upstream returns zero instances, so the access pattern
     * is auditable regardless of the upstream answer.
     */
    public List<String> listInstancesForStudy(String studyUid) {
        if (!properties.isEnabled()) return Collections.emptyList();
        if (studyUid == null || studyUid.isBlank()) return Collections.emptyList();

        List<String> instances = dicomWebClient
            .map(client -> client.qidoListInstances(studyUid))
            .orElse(Collections.emptyList());

        String shape = dicomWebClient.isPresent()
            ? "upstream returned " + instances.size() + " instances"
            : "audit-only (upstream client not configured)";
        emitAudit(studyUid,
            "DICOM proxy QIDO-RS lookup for study " + studyUid + " — " + shape);
        return instances;
    }

    /**
     * WADO-RS pixel fetch for an individual instance (row-42 follow-on).
     * Returns {@code null} when the flag is off, the upstream client
     * isn't configured, the studyUid/instanceUid is blank, or the
     * upstream responds 404. The controller renders {@code null} as
     * {@code 404 Not Found}; non-null bytes flow to the client as
     * {@code application/dicom}.
     */
    public byte[] fetchInstanceBytes(String studyUid, String instanceUid) {
        if (!properties.isEnabled()) return null;
        if (studyUid == null || studyUid.isBlank()
            || instanceUid == null || instanceUid.isBlank()) {
            return null;
        }
        byte[] bytes = dicomWebClient
            .map(client -> client.wadoFetchInstance(studyUid, instanceUid))
            .orElse(null);
        emitAudit(studyUid,
            "DICOM proxy WADO-RS fetch for study " + studyUid
                + " instance " + instanceUid
                + " — " + (bytes == null ? "not available" : (bytes.length + " bytes")));
        return bytes;
    }

    private void emitAudit(String studyUid, String description) {
        try {
            User caller = currentUserOrNull();
            AuditEventRequestDTO request = AuditEventRequestDTO.builder()
                .eventType(AuditEventType.IMAGING_RESULT_UPDATED)
                .status(AuditStatus.SUCCESS)
                .entityType(AUDIT_ENTITY_TYPE)
                .resourceId(studyUid)
                .userId(caller != null ? caller.getId() : null)
                .userName(caller != null ? caller.getUsername() : null)
                .eventDescription(description)
                .build();
            auditEventLogService.logEvent(request);
        } catch (RuntimeException ex) {
            log.warn("audit emission failed for DICOM proxy study {}: {}", studyUid, ex.toString());
        }
    }

    /**
     * Resolve the authenticated user so the audit row is attributed
     * to the clinician, not to SYSTEM. Caught on PR #349 Copilot
     * review (High severity).
     */
    private User currentUserOrNull() {
        String username = SecurityUtils.getCurrentUsername();
        if (username == null || username.isBlank()) return null;
        return userRepository.findByUsernameIgnoreCase(username).orElse(null);
    }
}
