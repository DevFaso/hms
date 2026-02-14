package com.example.hms.controller;

import com.example.hms.enums.ReferralAttachmentCategory;
import com.example.hms.payload.dto.ChartAttachmentUploadResponseDTO;
import com.example.hms.payload.dto.MessageResponse;
import com.example.hms.payload.dto.referral.ReferralAttachmentUploadResponseDTO;
import com.example.hms.service.FileUploadService;
import com.example.hms.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

/**
 * Controller for handling file uploads
 */
@Slf4j
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
@Tag(name = "File Upload API", description = "Handles file upload operations")
public class FileUploadController {

    private final FileUploadService fileUploadService;
    private final UserService userService;
    private static final String USER_NOT_FOUND_MESSAGE = "User not found";

    @Operation(summary = "Upload profile image", description = "Upload a profile image for the authenticated user")
    @PostMapping(value = "/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Object> uploadProfileImage(
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {

        try {
            // Get current user ID from authentication
            String username = authentication.getName();
            UUID userId = userService.getUserIdByUsername(username);

            if (userId == null) {
                return ResponseEntity.badRequest()
                        .body(new MessageResponse(USER_NOT_FOUND_MESSAGE));
            }

            // Upload the file
            String imageUrl = fileUploadService.uploadProfileImage(file, userId);

            // Update user's profile image URL
            userService.updateProfileImage(userId, imageUrl);

            log.info("Profile image uploaded successfully for user: {}", userId);

            return ResponseEntity.ok(Map.of(
                    "message", "Profile image uploaded successfully",
                    "imageUrl", imageUrl));

        } catch (IllegalArgumentException e) {
            log.warn("Invalid file upload request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(new MessageResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to upload profile image", e);
            return ResponseEntity.internalServerError()
                    .body(new MessageResponse("Failed to upload profile image"));
        }
    }

    @Operation(summary = "Delete profile image", description = "Delete the profile image for the authenticated user")
    @DeleteMapping("/profile-image")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Object> deleteProfileImage(Authentication authentication) {

        try {
            // Get current user ID from authentication
            String username = authentication.getName();
            UUID userId = userService.getUserIdByUsername(username);

            if (userId == null) {
                return ResponseEntity.badRequest()
                        .body(new MessageResponse(USER_NOT_FOUND_MESSAGE));
            }

            // Get current profile image URL
            String currentImageUrl = userService.getUserById(userId).getProfileImageUrl();

            // Delete the file
            if (currentImageUrl != null && !currentImageUrl.isEmpty()) {
                fileUploadService.deleteProfileImage(currentImageUrl);
            }

            // Update user's profile image URL to null
            userService.updateProfileImage(userId, null);

            log.info("Profile image deleted successfully for user: {}", userId);

            return ResponseEntity.ok(new MessageResponse("Profile image deleted successfully"));

        } catch (Exception e) {
            log.error("Failed to delete profile image", e);
            return ResponseEntity.internalServerError()
                    .body(new MessageResponse("Failed to delete profile image"));
        }
    }

    @Operation(
        summary = "Upload referral attachment",
        description = "Upload supporting documentation for OB-GYN referrals"
    )
    @PostMapping(value = "/referral-attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_NURSE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<Object> uploadReferralAttachment(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "category", required = false) ReferralAttachmentCategory category,
        @RequestParam(value = "displayName", required = false) String displayName,
        Authentication authentication
    ) {
        try {
            UUID userId = userService.getUserIdByUsername(authentication.getName());
            if (userId == null) {
                return ResponseEntity.badRequest().body(new MessageResponse(USER_NOT_FOUND_MESSAGE));
            }

            FileUploadService.StoredFileDescriptor descriptor =
                fileUploadService.uploadReferralAttachment(file, userId);

            ReferralAttachmentUploadResponseDTO response = ReferralAttachmentUploadResponseDTO.builder()
                .tempFileId(descriptor.storageKey())
                .publicUrl(descriptor.publicUrl())
                .displayName(StringUtils.hasText(displayName) ? displayName : descriptor.displayName())
                .category(category != null ? category : ReferralAttachmentCategory.OTHER)
                .contentType(descriptor.contentType())
                .sizeBytes(descriptor.sizeBytes())
                .build();

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid referral attachment upload: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(new MessageResponse(ex.getMessage()));
        } catch (Exception ex) {
            log.error("Failed to upload referral attachment", ex);
            return ResponseEntity.internalServerError()
                .body(new MessageResponse("Failed to upload referral attachment"));
        }
    }

    @Operation(
        summary = "Upload chart attachment",
        description = "Upload supplemental documents for patient chart updates"
    )
    @PostMapping(value = "/chart-attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<Object> uploadChartAttachment(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "label", required = false) String label,
        @RequestParam(value = "category", required = false) String category,
        Authentication authentication
    ) {
        try {
            UUID userId = userService.getUserIdByUsername(authentication.getName());
            if (userId == null) {
                return ResponseEntity.badRequest().body(new MessageResponse(USER_NOT_FOUND_MESSAGE));
            }

            FileUploadService.StoredFileDescriptor descriptor =
                fileUploadService.uploadChartAttachment(file, userId);

            ChartAttachmentUploadResponseDTO response = ChartAttachmentUploadResponseDTO.builder()
                .storageKey(descriptor.storageKey())
                .fileName(descriptor.displayName())
                .label(StringUtils.hasText(label) ? label : descriptor.displayName())
                .category(StringUtils.hasText(category) ? category : null)
                .contentType(descriptor.contentType())
                .sizeBytes(descriptor.sizeBytes())
                .sha256(descriptor.sha256())
                .downloadUrl(descriptor.publicUrl())
                .build();

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid chart attachment upload: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(new MessageResponse(ex.getMessage()));
        } catch (Exception ex) {
            log.error("Failed to upload chart attachment", ex);
            return ResponseEntity.internalServerError()
                .body(new MessageResponse("Failed to upload chart attachment"));
        }
    }
}