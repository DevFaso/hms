package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.imaging.dicom.DicomProxyService;
import com.example.hms.imaging.dicom.PacsUnavailableException;
import com.example.hms.imaging.dicom.PacsUrlPolicy;
import com.example.hms.imaging.dicom.RenderedFrame;
import com.example.hms.payload.dto.imaging.pacs.ImagingStudyViewerDTO;
import com.example.hms.service.imaging.ImagingStudyViewerService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * DICOM proxy controller (roadmap row 42, v2.0 / Clinical Depth).
 * Sits alongside the existing {@code /api/imaging/orders} and
 * {@code /api/imaging/reports} surfaces.
 *
 * <ul>
 *   <li>{@code GET /{studyUid}/instances}: the foundation-pass, platform-wide
 *       lookup; flag off ⇒ 404.</li>
 *   <li>{@code GET /reports/{reportId}/viewer} and
 *       {@code .../frames/{frame}/rendered}: the in-app viewer (V317), keyed by
 *       the report so the hospital scope is the report's, through the
 *       hospital's own PACS or the platform one. Image pixels are clinical
 *       content: a clinical role of the caller's own, never an admin role alone
 *       (user decision 2026-11-14).</li>
 * </ul>
 */
@RestController
@RequestMapping("/imaging/dicom")
public class DicomProxyController {

    private final DicomProxyService service;
    private final ImagingStudyViewerService viewerService;
    private final ControllerAuthUtils authUtils;

    public DicomProxyController(DicomProxyService service, ImagingStudyViewerService viewerService,
                                ControllerAuthUtils authUtils) {
        this.service = service;
        this.viewerService = viewerService;
        this.authUtils = authUtils;
    }

    @GetMapping("/{studyUid}/instances")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','RADIOLOGIST')")
    public ResponseEntity<List<String>> listInstances(@PathVariable String studyUid) {
        if (!service.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(service.listInstancesForStudy(studyUid));
    }

    /** The viewer manifest of a report: a status the portal words, and the instances when available. */
    @GetMapping("/reports/{reportId}/viewer")
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, "
        + "'DOCTOR','NURSE','MIDWIFE','RADIOLOGIST')")
    public ResponseEntity<ImagingStudyViewerDTO> viewer(@PathVariable UUID reportId, Authentication auth) {
        UUID actor = authUtils.resolveUserId(auth).orElse(null);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(viewerService.manifest(reportId, actor));
    }

    /** One rendered frame (JPEG or PNG), streamed through e-Keneya, never cached by the browser. */
    @GetMapping("/reports/{reportId}/series/{seriesUid}/instances/{instanceUid}/frames/{frame}/rendered")
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, "
        + "'DOCTOR','NURSE','MIDWIFE','RADIOLOGIST')")
    public ResponseEntity<byte[]> renderedFrame(@PathVariable UUID reportId, @PathVariable String seriesUid,
                                                @PathVariable String instanceUid, @PathVariable int frame) {
        try {
            RenderedFrame rendered = viewerService.frame(reportId, seriesUid, instanceUid, frame);
            return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(rendered.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .body(rendered.bytes());
        } catch (PacsUnavailableException | PacsUrlPolicy.RejectedException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).cacheControl(CacheControl.noStore()).build();
        }
    }
}
