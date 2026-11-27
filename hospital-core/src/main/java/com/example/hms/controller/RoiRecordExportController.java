package com.example.hms.controller;

import com.example.hms.security.chart.RestrictedChartPolicy;
import com.example.hms.service.PatientPortalService;
import com.example.hms.service.PatientRecordPdfService;
import com.example.hms.service.roi.RoiRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/**
 * The record released under an approved release-of-information request, as the hospital-owned release view (never
 * the caller's treatment view), with the chart export's redaction.
 *
 * <p>QA D4270: an approved copy is collected by the patient who asked for it (« Mes documents »), by the clinicians,
 * and by the hospital administrator who approved it; reception reads no clinical content and hands over a copy
 * produced by one of them. The service
 * decides per request ({@link RoiRequestService#copyForExport}, {@link RoiRequestService#copyForSelf}): the
 * annotations here only name the roles that may ask. Every download is audited, and nothing is returned when the
 * audit row cannot be written.
 */
@RestController @RequiredArgsConstructor
@RestrictedChartPolicy(value = RestrictedChartPolicy.Mode.NONE,
    reason = "the record released under an approved release-of-information request")
public class RoiRecordExportController {
    /**
     * The staff who may ask for an approved copy; an administrator only for a request they approved, and every role
     * held at the hospital the request belongs to (service). Reception reads no clinical content (owner rule
     * 2026-11-06): the desk hands over a copy a clinician or the approving administrator produced.
     */
    static final String COPY_ROLES = "hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE',"
        + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private final RoiRequestService requests;
    private final PatientRecordPdfService pdf;
    private final PatientPortalService portal;

    @GetMapping(value = "/roi-requests/{requestId}/record.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize(COPY_ROLES)
    public ResponseEntity<byte[]> download(@PathVariable UUID requestId) {
        byte[] bytes = pdf.renderForRoi(requests.copyForExport(requestId));
        return attachment(bytes, requestId);
    }

    /** The patient's own approved copy; another patient's request answers as a missing one. */
    @GetMapping(value = "/me/patient/roi-requests/{requestId}/record.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<byte[]> downloadOwn(@PathVariable UUID requestId, Authentication auth) {
        byte[] bytes = pdf.renderForRoi(portal.myApprovedRoiCopy(auth, requestId));
        return attachment(bytes, requestId);
    }

    private static ResponseEntity<byte[]> attachment(byte[] bytes, UUID requestId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"record-copy-" + requestId + ".pdf\"")
            .header("X-Content-Type-Options", "nosniff").contentType(MediaType.APPLICATION_PDF).body(bytes);
    }
}
