package com.example.hms.controller;

import com.example.hms.service.FileUploadService;
import com.example.hms.storage.FileStorage;
import com.example.hms.storage.StoredObject;
import com.example.hms.storage.StoredObjectResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Serves profile images (avatars) at the URL they always had,
 * {@code /uploads/profile-images/{name}}, from {@link FileStorage}: a local
 * directory or the private bucket. It replaces the static resource handler
 * that served the same path from the upload directory.
 *
 * <p>Deliberately without {@code @PreAuthorize}: avatars render through a
 * bare {@code <img src>} on the web and in both mobile apps, which cannot
 * carry a bearer token, and file names embed a random UUID. SecurityConfig
 * permits {@code GET /uploads/profile-images/**} and nothing else under
 * {@code /uploads}; this controller reads only keys under
 * {@code profile-images/}, so no other stored class (patient documents,
 * photos, attachments) is reachable through it.
 */
@RestController
@Tag(name = "Profile images", description = "Public avatar images")
public class ProfileImageController {

    /** One plain file name, as {@link FileUploadService#uploadProfileImage} builds them. */
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");
    private static final Duration CACHE_PERIOD = Duration.ofHours(1);

    private final FileStorage storage;
    /** Through a provider: a controller slice test has no ledger bean. */
    private final org.springframework.beans.factory.ObjectProvider<com.example.hms.upload.UploadLedger> ledger;

    public ProfileImageController(FileStorage storage,
            org.springframework.beans.factory.ObjectProvider<com.example.hms.upload.UploadLedger> ledger) {
        this.storage = storage;
        this.ledger = ledger;
    }

    @GetMapping("/uploads/" + FileUploadService.PROFILE_IMAGES_PATH + "/{fileName}")
    @Operation(summary = "A profile image",
        description = "Public (avatars render without a token); profile images only.")
    public ResponseEntity<Resource> profileImage(@PathVariable String fileName) throws IOException {
        if (!FILE_NAME.matcher(fileName).matches() || fileName.contains("..")) {
            return ResponseEntity.notFound().build();
        }
        int dot = fileName.lastIndexOf('.');
        String contentType = dot < 0 ? null : FileUploadService.imageContentTypeFor(fileName.substring(dot));
        if (contentType == null) {
            return ResponseEntity.notFound().build();
        }
        String key = FileUploadService.PROFILE_IMAGES_PATH + "/" + fileName;
        com.example.hms.upload.UploadLedger checks = ledger.getIfAvailable();
        if (checks != null) {
            // Not served while its malware check is pending, nor once it is refused.
            checks.requireServable(key);
        }
        Optional<StoredObject> stored = storage.get(key);
        if (stored.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // An avatar: a raster image by its checked extension, shown in the page.
        return com.example.hms.upload.UploadResponses.viewer(contentType, fileName)
            .cacheControl(CacheControl.maxAge(CACHE_PERIOD))
            .body(new StoredObjectResource(stored.get()));
    }
}
