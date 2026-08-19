package com.example.hms.controller;

import com.example.hms.imaging.dicom.DicomProxyService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * DICOM proxy controller (roadmap row 42, v2.0 / Clinical Depth).
 * Sits alongside the existing {@code /api/imaging/orders} and
 * {@code /api/imaging/reports} surfaces. Gated by
 * {@code app.imaging.dicom-proxy.enabled}; flag off ⇒ 404.
 *
 * <p>Allowlist mirrors the clinical roles that already read imaging
 * reports.
 */
@RestController
@RequestMapping("/imaging/dicom")
public class DicomProxyController {

    private final DicomProxyService service;

    public DicomProxyController(DicomProxyService service) {
        this.service = service;
    }

    @GetMapping("/{studyUid}/instances")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','NURSE','RADIOLOGIST')")
    public ResponseEntity<List<String>> listInstances(@PathVariable String studyUid) {
        if (!service.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(service.listInstancesForStudy(studyUid));
    }
}
