package com.example.hms.controller;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.imaging.PatientImagingStudyDTO;
import com.example.hms.service.PatientPortalService;
import com.example.hms.service.imaging.ImagingStudyImageService;
import com.example.hms.service.imaging.PatientImagingPortalService;
import com.example.hms.service.imaging.PatientImagingPortalService.Reader;
import com.example.hms.storage.StoredObjectResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
 * QA D2342 (r63, High): the patient's imaging reports and their key images,
 * on the portal's « Imagerie » page.
 *
 * <p>The patient id never comes from the request for the patient's own reads:
 * it is resolved from the token. A proxy names the grantor in the path, and
 * {@link PatientPortalService#resolveProxyRecordsPatientId} checks the active
 * grant before anything is read. Everything returned is released and is the
 * patient's copy ({@link PatientImagingPortalService}).
 */
@RestController
@RequestMapping("/me/patient")
@RequiredArgsConstructor
@Tag(name = "Patient Portal — Imaging", description = "The patient's released imaging reports and key images")
public class PatientImagingPortalController {

    private final PatientPortalService portalService;
    private final PatientImagingPortalService imagingPortalService;

    @Operation(summary = "My imaging studies with their released reports and key images")
    @GetMapping("/imaging")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<PatientImagingStudyDTO>>> getMyImaging(Authentication auth) {
        UUID patientId = portalService.resolvePatientId(auth);
        return ResponseEntity.ok(ApiResponseWrapper.success(imagingPortalService.studiesOf(patientId, Reader.PATIENT)));
    }

    @Operation(summary = "One key image of my released imaging study")
    @GetMapping("/imaging/images/{imageId}/content")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<Resource> getMyImagingImage(Authentication auth, @PathVariable UUID imageId) {
        UUID patientId = portalService.resolvePatientId(auth);
        return image(imagingPortalService.openImage(patientId, imageId, Reader.PATIENT));
    }

    @Operation(summary = "The grantor's imaging studies, as their proxy (records permission)")
    @GetMapping("/proxy-access/{patientId}/imaging")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponseWrapper<List<PatientImagingStudyDTO>>> getProxyImaging(
            Authentication auth, @PathVariable UUID patientId) {
        UUID grantorId = portalService.resolveProxyRecordsPatientId(auth, patientId);
        return ResponseEntity.ok(ApiResponseWrapper.success(imagingPortalService.studiesOf(grantorId, Reader.PROXY)));
    }

    @Operation(summary = "One key image of the grantor's released imaging study, as their proxy")
    @GetMapping("/proxy-access/{patientId}/imaging/images/{imageId}/content")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> getProxyImagingImage(
            Authentication auth, @PathVariable UUID patientId, @PathVariable UUID imageId) {
        UUID grantorId = portalService.resolveProxyRecordsPatientId(auth, patientId);
        return image(imagingPortalService.openImage(grantorId, imageId, Reader.PROXY));
    }

    private static ResponseEntity<Resource> image(ImagingStudyImageService.ImageContent content) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.contentType()))
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                .filename("imaging-image-" + content.imageId() + extensionOf(content.contentType()))
                .build()
                .toString())
            .body(new StoredObjectResource(content.object()));
    }

    private static String extensionOf(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "application/pdf" -> ".pdf";
            default -> ".jpg";
        };
    }
}
