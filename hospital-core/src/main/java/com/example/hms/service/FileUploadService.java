package com.example.hms.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;

/**
 * Service for handling file uploads, particularly profile images
 */
@Slf4j
@Service
public class FileUploadService {

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Value("${app.backend.base-url:http://localhost:8081}")
    private String backendBaseUrl;

    private static final long MAX_REFERRAL_ATTACHMENT_SIZE = 20L * 1024 * 1024; // 20 MB
    private static final Set<String> ALLOWED_ATTACHMENT_EXTENSIONS = Set.of(
        ".pdf", ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".tiff", ".txt", ".rtf", ".doc",
        ".docx"
    );

    /**
     * Upload a profile image for a user
     * 
     * @param file   The uploaded file
     * @param userId The user ID
     * @return The URL path to access the uploaded file
     * @throws IOException if upload fails
     */
    public String uploadProfileImage(MultipartFile file, UUID userId) throws IOException {
        validateImageFile(file);

        // Create uploads directory if it doesn't exist
        Path uploadPath = Paths.get(uploadDir, "profile-images");
        Files.createDirectories(uploadPath);

        String originalFilename = file.getOriginalFilename();
        String extension = getFileExtension(originalFilename);
        String filename = userId + "_profile_" + System.currentTimeMillis() + extension;

        Path filePath = uploadPath.resolve(filename);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        log.info("Profile image uploaded successfully: {}", filePath);

        String relativePath = "/uploads/profile-images/" + filename;
        return buildPublicUrl(relativePath);
    }

    /**
     * Delete an existing profile image
     * 
     * @param imageUrl The image URL to delete
     */
    public void deleteProfileImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return;
        }

        try {
            // Extract filename from URL (e.g., "/api/uploads/profile-images/filename.jpg"
            // -> "filename.jpg")
            String filename = imageUrl.substring(imageUrl.lastIndexOf('/') + 1);
            Path filePath = Paths.get(uploadDir, "profile-images", filename);

            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.info("Profile image deleted successfully: {}", filePath);
            }
        } catch (Exception e) {
            log.warn("Failed to delete profile image: {}", imageUrl, e);
        }
    }

    public StoredFileDescriptor uploadReferralAttachment(MultipartFile file, UUID uploaderId) throws IOException {
        validateAttachmentFile(file);

        Path uploadPath = Paths.get(uploadDir, "referral-attachments");
        Files.createDirectories(uploadPath);

        String sanitizedBaseName = sanitizeBaseFilename(file.getOriginalFilename());
        String extension = getFileExtension(file.getOriginalFilename());
        String filename = buildAttachmentFilename(sanitizedBaseName, extension, uploaderId, "attachment");

        Path filePath = uploadPath.resolve(filename);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        String relativePath = "/uploads/referral-attachments/" + filename;
        return new StoredFileDescriptor(
            relativePath,
            buildPublicUrl(relativePath),
            determineDisplayName(file, filename),
            file.getContentType(),
            file.getSize(),
            null
        );
    }

    public StoredFileDescriptor uploadChartAttachment(MultipartFile file, UUID uploaderId) throws IOException {
        validateAttachmentFile(file);

        Path uploadPath = Paths.get(uploadDir, "chart-attachments");
        Files.createDirectories(uploadPath);

        String sanitizedBaseName = sanitizeBaseFilename(file.getOriginalFilename());
        String extension = getFileExtension(file.getOriginalFilename());
        String filename = buildAttachmentFilename(sanitizedBaseName, extension, uploaderId, "chart_attachment");

        Path filePath = uploadPath.resolve(filename);
        MessageDigest digest = createSha256Digest();
        try (DigestInputStream digestStream = new DigestInputStream(file.getInputStream(), digest)) {
            Files.copy(digestStream, filePath, StandardCopyOption.REPLACE_EXISTING);
        }

        String relativePath = "/uploads/chart-attachments/" + filename;
        long sizeBytes = Files.size(filePath);
        String checksum = HexFormat.of().formatHex(digest.digest());

        return new StoredFileDescriptor(
            relativePath,
            buildPublicUrl(relativePath),
            determineDisplayName(file, filename),
            file.getContentType(),
            sizeBytes,
            checksum
        );
    }

    private String buildAttachmentFilename(String sanitizedBaseName, String extension, UUID uploaderId, String fallbackBaseName) {
        String prefix = uploaderId != null ? uploaderId + "_" : "";
        String base = sanitizedBaseName.isBlank() ? fallbackBaseName : sanitizedBaseName;
        return prefix + base + "_" + UUID.randomUUID() + extension;
    }

    private String determineDisplayName(MultipartFile file, String fallbackName) {
        return StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : fallbackName;
    }

    private MessageDigest createSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest algorithm is not available", ex);
        }
    }

    /**
     * Validate that the uploaded file is a valid image
     */
    private void validateImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }

        // Check file size (max 5MB)
        long maxSize = 5L * 1024 * 1024; // 5MB
        if (file.getSize() > maxSize) {
            throw new IllegalArgumentException("File size cannot exceed 5MB");
        }

        // Check content type
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("File must be an image");
        }

        // Check allowed extensions
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new IllegalArgumentException("Filename cannot be null");
        }

        String extension = getFileExtension(originalFilename).toLowerCase();
        if (!extension.matches("\\.(jpg|jpeg|png|gif|webp)$")) {
            throw new IllegalArgumentException("Only JPG, PNG, GIF, and WebP images are allowed");
        }
    }

    private void validateAttachmentFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }

        if (file.getSize() > MAX_REFERRAL_ATTACHMENT_SIZE) {
            throw new IllegalArgumentException("Attachment size cannot exceed 20MB");
        }

        String extension = getFileExtension(file.getOriginalFilename()).toLowerCase();
        if (!ALLOWED_ATTACHMENT_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Unsupported attachment type");
        }
    }

    /**
     * Extract file extension from filename
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.'));
    }

    private String sanitizeBaseFilename(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            return "file";
        }

        String candidate;
        try {
            candidate = Paths.get(originalFilename).getFileName().toString();
        } catch (Exception invalidPath) {
            log.debug("[Upload] Unable to derive filename from '{}' - {}", originalFilename, invalidPath.getMessage());
            candidate = originalFilename;
        }

        String baseName = candidate.replaceAll("[^a-zA-Z0-9-_]", "_");
        if (!StringUtils.hasText(baseName)) {
            baseName = "file";
        }

        // Ensure no path separators survived sanitization
        baseName = baseName.replace('/', '_').replace('\\', '_');

        if (baseName.length() > 80) {
            baseName = baseName.substring(0, 80);
        }

        return baseName;
    }

    private String buildPublicUrl(String relativePath) {
        String normalized = relativePath.startsWith("/") ? relativePath : "/" + relativePath;

        try {
            return ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path(normalized)
                    .build()
                    .toUriString();
        } catch (IllegalStateException ex) {
            // No request context (e.g., async execution) – fall back to configured backend base URL
            return UriComponentsBuilder.fromUriString(backendBaseUrl)
                    .path(normalized)
                    .build()
                    .toUriString();
        }
    }

    public record StoredFileDescriptor(
        String storageKey,
        String publicUrl,
        String displayName,
        String contentType,
        long sizeBytes,
        String sha256
    ) {
    }
}