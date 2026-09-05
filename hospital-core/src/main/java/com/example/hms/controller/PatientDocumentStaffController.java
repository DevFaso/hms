package com.example.hms.controller;

import com.example.hms.enums.PatientDocumentType;
import com.example.hms.payload.dto.portal.PatientDocumentResponseDTO;
import com.example.hms.service.PatientDocumentService;
import com.example.hms.utility.RoleValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Staff-side read of what a patient uploaded through the portal.
 *
 * <p>Why a separate controller: the patient's own surface lives on
 * {@code /me/patient/documents} and is ownership-checked on the calling
 * patient. Staff read through the chart, so the routes sit under
 * {@code /patients/{patientId}/...}, which the
 * {@link com.example.hms.security.audit.PatientAccessAuditInterceptor}
 * already audits by path convention; the download additionally writes its
 * own DATA_ACCESS row in the service. Tenant gate: the caller's active
 * hospital (X-Hospital-Id for a super-admin, the assignment otherwise) and
 * the patient's registration there — see
 * {@code PatientDocumentService#listForPatient}.
 *
 * <p>Bytes stream only through {@code /download} with a bearer token; the
 * stored {@code fileUrl} column is never returned as a link.
 */
@RestController
@RequestMapping("/patients/{patientId}/documents")
@RequiredArgsConstructor
@Tag(name = "Patient Documents (staff)",
    description = "Read patient-uploaded documents from the chart — hospital-scoped, audited")
@SecurityRequirement(name = "bearerAuth")
public class PatientDocumentStaffController {

    /** Clinical and front-desk roles that read a chart; mirrors the portal tab gate. */
    static final String READ_ROLES = "hasAnyAuthority("
        + "'ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_PHARMACIST','ROLE_RECEPTIONIST',"
        + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private final PatientDocumentService documentService;
    private final RoleValidator roleValidator;

    @Operation(summary = "List a patient's uploaded documents",
        description = "Live (non-deleted) documents the patient uploaded, newest first. Requires an active "
            + "hospital the patient is registered at; 400 without one, 404 when the patient is not "
            + "registered there.")
    @GetMapping
    @PreAuthorize(READ_ROLES)
    public ResponseEntity<Page<PatientDocumentResponseDTO>> list(
        @PathVariable UUID patientId,
        @RequestParam(required = false) PatientDocumentType documentType,
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        return ResponseEntity.ok(documentService.listForPatient(hospitalId, patientId, documentType, pageable));
    }

    @Operation(summary = "Get one uploaded document's metadata")
    @GetMapping("/{documentId}")
    @PreAuthorize(READ_ROLES)
    public ResponseEntity<PatientDocumentResponseDTO> get(
        @PathVariable UUID patientId,
        @PathVariable UUID documentId
    ) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        return ResponseEntity.ok(documentService.getForPatient(hospitalId, patientId, documentId));
    }

    @Operation(summary = "Download an uploaded document",
        description = "Authenticated, hospital-scoped streaming; every call is audited as DATA_ACCESS.")
    @GetMapping("/{documentId}/download")
    @PreAuthorize(READ_ROLES)
    public ResponseEntity<Resource> download(
        @PathVariable UUID patientId,
        @PathVariable UUID documentId
    ) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        PatientDocumentService.DocumentPayload payload =
            documentService.downloadForPatient(hospitalId, patientId, documentId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(payload.contentType()))
            .cacheControl(CacheControl.noStore())
            // The display name is the patient's own file name: quotes, backslashes,
            // control characters and non-ASCII all reach here. Spring's builder
            // emits the RFC 5987 form (filename*=UTF-8''…) so none of it can break
            // the header or the response.
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(payload.displayName(), StandardCharsets.UTF_8)
                .build()
                .toString())
            .body(new FileSystemResource(payload.path()));
    }
}
