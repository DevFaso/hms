package com.example.hms.controller;

import com.example.hms.payload.dto.imaging.ImagingStudyImageDTO;
import com.example.hms.service.imaging.ImagingStudyImageService;
import com.example.hms.storage.StoredObjectResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Key images on an imaging study (owner 2026-11-19, V316).
 *
 * <p><b>Class-level {@code @PreAuthorize} is load-bearing</b>, as on
 * {@link ImagingResultController}: there is no {@code /imaging/**} matcher in
 * {@code SecurityConfig}, so the class rule (the union of the method rules)
 * keeps a forgotten method closed. Reading admits exactly the roles that read
 * the report ({@code GET /imaging/results/order/{orderId}}); reception and
 * billing are not among them. Writing takes a radiologist or a doctor acting
 * in their own clinical role ({@code ClinicalActor}), as authoring a report does.
 */
@RestController
@RequestMapping("/imaging")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','ADMIN','DOCTOR','NURSE','MIDWIFE','RADIOLOGIST','PATIENT')")
@Tag(name = "Imaging Images", description = "Key images and exported PDFs on an imaging study or report")
public class ImagingStudyImageController {

    static final String READ_ROLES =
        "hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','ADMIN','DOCTOR','NURSE','MIDWIFE','RADIOLOGIST','PATIENT')";
    static final String WRITE_ROLES =
        "T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'RADIOLOGIST','DOCTOR')";

    private final ImagingStudyImageService imageService;

    @PostMapping(value = "/orders/{orderId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(WRITE_ROLES)
    @Operation(summary = "Attach a key image to an imaging study",
        description = "JPEG, PNG, WebP or PDF, content-checked, 10 MB at most. With reportId the image "
            + "goes on that unsigned report; without it, on the study itself (refused once the study "
            + "has a signed report: add it to an addendum).")
    public ResponseEntity<ImagingStudyImageDTO> upload(
        @PathVariable UUID orderId,
        @RequestPart("file") MultipartFile file,
        @RequestParam(value = "reportId", required = false) UUID reportId,
        @RequestParam(value = "caption", required = false) String caption
    ) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(imageService.upload(orderId, reportId, file, caption));
    }

    @GetMapping("/orders/{orderId}/images")
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "List the key images of an imaging study",
        description = "Read as the report is read: hospital-scoped, released images only for an "
            + "administrator or the patient.")
    public ResponseEntity<List<ImagingStudyImageDTO>> list(@PathVariable UUID orderId) {
        return ResponseEntity.ok(imageService.list(orderId));
    }

    @GetMapping("/images/{imageId}/content")
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "Read one key image",
        description = "Authenticated streaming, shown inline, never cached; every read is audited.")
    public ResponseEntity<Resource> content(@PathVariable UUID imageId) {
        ImagingStudyImageService.ImageContent content = imageService.readContent(imageId);
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

    @DeleteMapping("/images/{imageId}")
    @PreAuthorize(WRITE_ROLES)
    @Operation(summary = "Remove a key image",
        description = "Its uploader or a radiologist, before the report it belongs to is signed.")
    public ResponseEntity<Void> delete(@PathVariable UUID imageId) throws IOException {
        imageService.delete(imageId);
        return ResponseEntity.noContent().build();
    }

    /** QA D2464 (r64): the reason a locked image is withdrawn. */
    public record WithdrawRequest(@jakarta.validation.constraints.NotBlank
                                  @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    /** QA D2466 (r64): an image's corrected caption; required, and an empty one clears it. */
    public record CaptionRequest(@jakarta.validation.constraints.NotNull
                                 @jakarta.validation.constraints.Size(max = 500) String caption) {
    }

    @GetMapping("/orders/{orderId}/images/withdrawn")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','ADMIN','DOCTOR','NURSE','MIDWIFE','RADIOLOGIST')")
    @Operation(summary = "The study's withdrawn key images, for the record",
        description = "When, by whom and why each was withdrawn; never the bytes. Hospital-scoped.")
    public ResponseEntity<List<com.example.hms.payload.dto.imaging.ImagingStudyImageWithdrawalDTO>> withdrawn(
            @PathVariable UUID orderId) {
        return ResponseEntity.ok(imageService.withdrawn(orderId));
    }

    @PostMapping("/images/{imageId}/withdraw")
    @PreAuthorize(WRITE_ROLES)
    @Operation(summary = "Withdraw a key image after signing",
        description = "A radiologist or the report's signer, with a reason. The file and the record are kept and "
            + "audited; every reader, the patient included, stops seeing the image.")
    public ResponseEntity<Void> withdraw(@PathVariable UUID imageId,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody WithdrawRequest request) {
        imageService.withdraw(imageId, request != null ? request.reason() : null);
        return ResponseEntity.noContent().build();
    }

    @org.springframework.web.bind.annotation.PutMapping("/images/{imageId}/caption")
    @PreAuthorize(WRITE_ROLES)
    @Operation(summary = "Correct a key image's caption",
        description = "Its uploader or a radiologist; every correction is audited.")
    public ResponseEntity<ImagingStudyImageDTO> updateCaption(@PathVariable UUID imageId,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody CaptionRequest request) {
        return ResponseEntity.ok(imageService.updateCaption(imageId, request != null ? request.caption() : null));
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
