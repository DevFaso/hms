package com.example.hms.controller;

import ca.uhn.fhir.context.FhirContext;
import com.example.hms.fhir.everything.PatientEverythingService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.Bundle;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Tier 2 item 44 — single-patient record download for the portal, which was
 * print-only until now. Streams the patient's full FHIR bundle (the merged,
 * link-free variant of {@code Patient/{id}/$everything}) as a downloadable
 * {@code application/fhir+json} file.
 *
 * <p>Clinical + admin roles only — a whole-record PHI export is a wider
 * disclosure than opening a chart tab, so the receptionist chart roles do
 * not carry over. Tenancy and the registration gate are enforced inside
 * {@link PatientEverythingService}, which also audits every exported page
 * ({@code PATIENT_EXPORT} trail).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/patients")
public class PatientRecordExportController {

    static final String EXPORT_ROLES = "hasAnyAuthority("
        + "'ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private final PatientEverythingService everythingService;
    private final FhirContext fhirContext;

    @Operation(summary = "Download a patient's full record as a FHIR bundle",
        description = "One self-contained application/fhir+json file (no pagination links). "
            + "Audited as a PHI export; requires the caller's active hospital scope and the "
            + "patient's registration there.")
    @GetMapping(value = "/{patientId}/fhir-record", produces = "application/fhir+json")
    @PreAuthorize(EXPORT_ROLES)
    public ResponseEntity<String> downloadRecord(@PathVariable UUID patientId) {
        Bundle bundle = everythingService.fullRecordForDownload(patientId);
        String json = fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle);
        log.info("📦 Patient record export streamed for patient '{}' ({} entries)",
            patientId, bundle.getEntry().size());
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"patient-record-" + patientId + ".json\"")
            .contentType(MediaType.parseMediaType("application/fhir+json"))
            .body(json);
    }
}
