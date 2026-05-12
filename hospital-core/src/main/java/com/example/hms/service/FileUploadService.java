package com.example.hms.service;

import com.example.hms.enums.ChatAttachmentKind;
import jakarta.annotation.PostConstruct;
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
    private static final String PROFILE_IMAGES_PATH = "profile-images";


    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    /**
     * Browser-facing URL prefix for <em>patient document</em> uploads only.
     * Defaults to {@code /uploads} which matches the historical hard-coded
     * value used by {@link #uploadPatientDocument}. Sonar S1075 — externalized
     * so deployments fronted by a reverse-proxy with a different static
     * alias (e.g. {@code /static/files}) can override without a code change.
     *
     * <p><b>Scope intentionally limited to patient documents.</b> The other
     * upload paths in this class (profile images, chat attachments via
     * {@code CHAT_ATTACHMENT_KEY_PREFIX}, referral/chart attachments via
     * {@link #relativeUploadPath}) still embed {@code /uploads} literally
     * because their values double as <b>persisted storage keys</b> on
     * existing DB rows — flipping them via config would orphan historical
     * keys. Touching those paths needs a data migration and is out of scope
     * for the original sonar S1075 cleanup. See PR #315 review notes.
     *
     * <p>Normalised at startup ({@link #normalizePatientDocumentUrlPrefix})
     * so configured values like {@code "uploads"}, {@code "/uploads"},
     * {@code "/uploads/"} or even {@code "//uploads//"} all produce the
     * same storage-key shape (single leading slash, no trailing slash).
     */
    @Value("${app.upload.patient-document-url-prefix:/uploads}")
    private String patientDocumentUrlPrefix;

    /**
     * Sonar S1075 + PR #315 review — normalise the configured prefix so that
     * downstream concatenation ({@code prefix + "/" + subdir + "/" + filename})
     * cannot produce {@code //} or a slashless leading char, regardless of
     * whether ops wrote {@code "uploads"}, {@code "/uploads"}, {@code "/uploads/"},
     * or even {@code "//uploads//"}. Idempotent.
     */
    @PostConstruct
    void normalizePatientDocumentUrlPrefix() {
        patientDocumentUrlPrefix = normalizeUrlPathPrefix(patientDocumentUrlPrefix, "/uploads");
    }

    /**
     * Returns {@code raw} with exactly one leading slash and no trailing slash.
     * Falls back to {@code fallback} (already in the desired shape) when the
     * raw value is null/blank.
     */
    static String normalizeUrlPathPrefix(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String p = raw.trim();
        // Collapse repeated leading slashes to a single one.
        while (p.startsWith("//")) {
            p = p.substring(1);
        }
        // Strip ALL trailing slashes — concatenation supplies the separator.
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return p;
    }

    /**
     * Public base URL for file links returned to the browser.
     * <p>
     * Set this to your public-facing domain, e.g.
     * {@code https://hms.dev.bitnesttechs.com} so that the browser can reach the
     * uploaded files through the Nginx reverse-proxy, not through an internal
     * Railway hostname that is unreachable from the public internet.
     * <p>
     * The value is intentionally left <em>empty</em> by default so that the
     * {@link #buildPublicUrl} method falls back to
     * {@link ServletUriComponentsBuilder#fromCurrentContextPath()} when running
     * locally (where no public URL is configured).  In production you must set
     * this environment variable / Spring property.
     */
    @Value("${app.public-base-url:}")
    private String publicBaseUrl;

    /**
     * Legacy fallback used only when there is no active request context AND
     * {@code app.public-base-url} is not configured.  Kept for backward
     * compatibility with unit-tests and background jobs that call upload helpers
     * outside of an HTTP request thread.
     */
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
        Path uploadPath = Paths.get(uploadDir, PROFILE_IMAGES_PATH);
        Files.createDirectories(uploadPath);

        String originalFilename = file.getOriginalFilename();
        String extension = getFileExtension(originalFilename);
        String filename = userId + "_profile_" + System.currentTimeMillis() + extension;

        Path filePath = uploadPath.resolve(filename).normalize();
        if (!filePath.startsWith(uploadPath)) {
            throw new IllegalArgumentException("Invalid file path — path traversal attempt detected");
        }
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
            Path filePath = Paths.get(uploadDir, PROFILE_IMAGES_PATH, filename).normalize();
            Path expectedParent = Paths.get(uploadDir, PROFILE_IMAGES_PATH).normalize();
            if (!filePath.startsWith(expectedParent)) {
                log.warn("Path traversal attempt detected in deleteProfileImage: {}", imageUrl);
                return;
            }

            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.info("Profile image deleted successfully: {}", filePath);
            }
        } catch (IOException | RuntimeException e) {
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

        Path filePath = uploadPath.resolve(filename).normalize();
        if (!filePath.startsWith(uploadPath)) {
            throw new IllegalArgumentException("Invalid file path — path traversal attempt detected");
        }
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

        Path filePath = uploadPath.resolve(filename).normalize();
        if (!filePath.startsWith(uploadPath)) {
            throw new IllegalArgumentException("Invalid file path — path traversal attempt detected");
        }
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
        } catch (RuntimeException invalidPath) {
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

        // Prefer the explicitly configured public URL (e.g. https://hms.dev.bitnesttechs.com).
        // This avoids leaking the internal Railway hostname to the browser.
        // The Spring Boot context-path is "/api", so the resource handler registered at
        // "/uploads/**" is reachable from the browser at "/api/uploads/**" (via Nginx which
        // proxies /api/ → backend).  We therefore prepend "/api" here so every generated URL
        // includes the correct context-path prefix.
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            return UriComponentsBuilder.fromUriString(publicBaseUrl)
                    .path("/api")
                    .path(normalized)
                    .build()
                    .toUriString();
        }

        // Inside an active HTTP request context (local dev) use the current origin.
        try {
            return ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path(normalized)
                    .build()
                    .toUriString();
        } catch (IllegalStateException ex) {
            // No request context (e.g., async execution) – fall back to backend base URL.
            return UriComponentsBuilder.fromUriString(backendBaseUrl)
                    .path(normalized)
                    .build()
                    .toUriString();
        }
    }

    private static final long MAX_PATIENT_DOCUMENT_SIZE = 20L * 1024 * 1024; // 20 MB
    private static final String PATIENT_DOCUMENTS_PATH = "patient-documents";

    /**
     * Upload a document submitted by a patient through the patient portal.
     * Supports PDF, common image types, and text documents.
     *
     * @param file     the uploaded file (multipart)
     * @param userId   the ID of the uploading user (patient or proxy)
     * @return {@link StoredFileDescriptor} with storage key, public URL, and checksum
     * @throws IOException if the file cannot be written to disk
     */
    public StoredFileDescriptor uploadPatientDocument(MultipartFile file, UUID userId) throws IOException {
        validatePatientDocumentFile(file);

        Path uploadPath = Paths.get(uploadDir, PATIENT_DOCUMENTS_PATH);
        Files.createDirectories(uploadPath);

        String sanitizedBaseName = sanitizeBaseFilename(file.getOriginalFilename());
        String extension = getFileExtension(file.getOriginalFilename());
        String filename = buildAttachmentFilename(sanitizedBaseName, extension, userId, "patient_doc");

        Path filePath = uploadPath.resolve(filename).normalize();
        if (!filePath.startsWith(uploadPath)) {
            throw new IllegalArgumentException("Invalid file path — path traversal attempt detected");
        }

        MessageDigest digest = createSha256Digest();
        try (DigestInputStream digestStream = new DigestInputStream(file.getInputStream(), digest)) {
            Files.copy(digestStream, filePath, StandardCopyOption.REPLACE_EXISTING);
        }

        String relativePath = patientDocumentUrlPrefix + "/" + PATIENT_DOCUMENTS_PATH + "/" + filename;
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

    private void validatePatientDocumentFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }

        if (file.getSize() > MAX_PATIENT_DOCUMENT_SIZE) {
            throw new IllegalArgumentException("Document size cannot exceed 20MB");
        }

        String extension = getFileExtension(file.getOriginalFilename()).toLowerCase();
        if (!ALLOWED_ATTACHMENT_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Unsupported document type. Allowed: PDF, JPG, JPEG, PNG, GIF, BMP, TIFF, TXT, RTF, DOC, DOCX");
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

    // ---------------------------------------------------------------------
    // P1 #10 — Telehealth low-bandwidth: chat attachments (PHOTO / AUDIO).
    // Photo cap is intentionally lower than chart-attachments so a phone
    // on a metered link doesn't blow a patient's data plan; audio cap is
    // tighter still since voice memos are tiny in Opus.
    // ---------------------------------------------------------------------
    private static final long MAX_CHAT_PHOTO_SIZE = 10L * 1024 * 1024; // 10 MB
    private static final long MAX_CHAT_AUDIO_SIZE = 5L * 1024 * 1024;  // 5 MB
    private static final String CHAT_ATTACHMENTS_PATH = "chat-attachments";
    private static final Set<String> ALLOWED_CHAT_PHOTO_EXTENSIONS = Set.of(
        ".jpg", ".jpeg", ".png", ".webp"
    );
    private static final Set<String> ALLOWED_CHAT_PHOTO_MIME = Set.of(
        "image/jpeg", "image/png", "image/webp"
    );
    private static final Set<String> ALLOWED_CHAT_AUDIO_EXTENSIONS = Set.of(
        ".webm", ".ogg", ".oga", ".m4a", ".mp4", ".mp3", ".aac"
    );
    private static final Set<String> ALLOWED_CHAT_AUDIO_MIME = Set.of(
        "audio/webm", "audio/ogg", "audio/mp4", "audio/mpeg", "audio/aac", "audio/x-m4a"
    );

    /**
     * Upload a single chat-attachment for a low-bandwidth telehealth consult.
     * Returns the storage descriptor; the caller (chat send path) links it to
     * the message row and records the SHA-256 for tamper detection.
     *
     * @throws IllegalArgumentException if the kind/content-type/extension/size do not match
     */
    public StoredFileDescriptor uploadChatAttachment(MultipartFile file,
                                                      ChatAttachmentKind kind,
                                                      UUID uploaderId) throws IOException {
        validateChatAttachment(file, kind);

        Path uploadPath = Paths.get(uploadDir, CHAT_ATTACHMENTS_PATH);
        Files.createDirectories(uploadPath);

        String sanitizedBaseName = sanitizeBaseFilename(file.getOriginalFilename());
        String extension = getFileExtension(file.getOriginalFilename());
        String filename = buildAttachmentFilename(sanitizedBaseName, extension, uploaderId,
            kind == ChatAttachmentKind.AUDIO ? "voice_memo" : "photo");

        Path filePath = uploadPath.resolve(filename).normalize();
        if (!filePath.startsWith(uploadPath)) {
            throw new IllegalArgumentException("Invalid file path — path traversal attempt detected");
        }
        MessageDigest digest = createSha256Digest();
        try (DigestInputStream digestStream = new DigestInputStream(file.getInputStream(), digest)) {
            Files.copy(digestStream, filePath, StandardCopyOption.REPLACE_EXISTING);
        }

        String relativePath = relativeUploadPath(CHAT_ATTACHMENTS_PATH, filename);
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

    private static String relativeUploadPath(String subdir, String filename) {
        return String.join("/", "/uploads", subdir, filename);
    }

    private static final String CHAT_ATTACHMENT_KEY_PREFIX = "/uploads/chat-attachments/";

    /**
     * Re-derive a server-trusted descriptor from a previously uploaded chat-attachment storage
     * key. The caller (chat send path) only supplies the storage key + declared kind +
     * (for AUDIO) the client-measured duration; everything else (size, sha256, content type,
     * display name, public url) is recomputed from disk so a tampered or omitted client
     * descriptor cannot poison the persisted row.
     *
     * @throws IllegalArgumentException if the key is malformed, the file is missing, or kind
     *     mismatches the on-disk content type
     */
    public StoredFileDescriptor resolveChatAttachment(String storageKey, ChatAttachmentKind declaredKind) throws IOException {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("Attachment storageKey is required");
        }
        if (declaredKind == null) {
            throw new IllegalArgumentException("Attachment kind is required");
        }
        if (!storageKey.startsWith(CHAT_ATTACHMENT_KEY_PREFIX)) {
            throw new IllegalArgumentException("Invalid chat attachment storageKey");
        }
        String filename = storageKey.substring(CHAT_ATTACHMENT_KEY_PREFIX.length());
        if (filename.isBlank() || filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            throw new IllegalArgumentException("Invalid chat attachment storageKey");
        }
        Path uploadPath = Paths.get(uploadDir, CHAT_ATTACHMENTS_PATH).toAbsolutePath().normalize();
        Path filePath = uploadPath.resolve(filename).normalize();
        if (!filePath.startsWith(uploadPath)) {
            throw new IllegalArgumentException("Invalid chat attachment storageKey");
        }
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw new IllegalArgumentException("Chat attachment not found");
        }

        String extension = getFileExtension(filename).toLowerCase();
        ChatAttachmentKind derivedKind = deriveChatAttachmentKindFromExtension(extension);
        if (derivedKind == null) {
            throw new IllegalArgumentException("Unsupported chat attachment extension");
        }
        if (derivedKind != declaredKind) {
            throw new IllegalArgumentException("Declared kind does not match stored file");
        }

        long sizeBytes = Files.size(filePath);
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("Chat attachment is empty");
        }
        if (declaredKind == ChatAttachmentKind.PHOTO && sizeBytes > MAX_CHAT_PHOTO_SIZE) {
            throw new IllegalArgumentException("Photo cannot exceed 10MB");
        }
        if (declaredKind == ChatAttachmentKind.AUDIO && sizeBytes > MAX_CHAT_AUDIO_SIZE) {
            throw new IllegalArgumentException("Voice memo cannot exceed 5MB");
        }

        String contentType = resolveChatContentTypeFromExtension(extension);
        String sha256 = computeSha256(filePath);
        String relativePath = CHAT_ATTACHMENT_KEY_PREFIX + filename;

        return new StoredFileDescriptor(
            relativePath,
            buildPublicUrl(relativePath),
            filename,
            contentType,
            sizeBytes,
            sha256
        );
    }

    private ChatAttachmentKind deriveChatAttachmentKindFromExtension(String extension) {
        if (ALLOWED_CHAT_PHOTO_EXTENSIONS.contains(extension)) return ChatAttachmentKind.PHOTO;
        if (ALLOWED_CHAT_AUDIO_EXTENSIONS.contains(extension)) return ChatAttachmentKind.AUDIO;
        return null;
    }

    private String resolveChatContentTypeFromExtension(String extension) {
        return switch (extension) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".png" -> "image/png";
            case ".webp" -> "image/webp";
            case ".webm" -> "audio/webm";
            case ".ogg", ".oga" -> "audio/ogg";
            case ".m4a", ".mp4" -> "audio/mp4";
            case ".mp3" -> "audio/mpeg";
            case ".aac" -> "audio/aac";
            default -> "application/octet-stream";
        };
    }

    private String computeSha256(Path filePath) throws IOException {
        MessageDigest digest = createSha256Digest();
        try (java.io.InputStream in = Files.newInputStream(filePath);
             DigestInputStream digestStream = new DigestInputStream(in, digest)) {
            byte[] buf = new byte[8192];
            while (digestStream.read(buf) != -1) {
                // drain to digest
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void validateChatAttachment(MultipartFile file, ChatAttachmentKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("Attachment kind is required");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }
        if (kind == ChatAttachmentKind.AUDIO) {
            validateChatAudio(file);
        } else {
            validateChatPhoto(file);
        }
    }

    private void validateChatPhoto(MultipartFile file) {
        if (file.getSize() > MAX_CHAT_PHOTO_SIZE) {
            throw new IllegalArgumentException("Photo cannot exceed 10MB");
        }
        String extension = getFileExtension(file.getOriginalFilename()).toLowerCase();
        if (!ALLOWED_CHAT_PHOTO_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Unsupported photo format. Allowed: JPG, JPEG, PNG, WEBP");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CHAT_PHOTO_MIME.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("Unsupported photo content-type");
        }
    }

    private void validateChatAudio(MultipartFile file) {
        if (file.getSize() > MAX_CHAT_AUDIO_SIZE) {
            throw new IllegalArgumentException("Voice memo cannot exceed 5MB");
        }
        String extension = getFileExtension(file.getOriginalFilename()).toLowerCase();
        if (!ALLOWED_CHAT_AUDIO_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Unsupported audio format. Allowed: WEBM, OGG, M4A, MP4, MP3, AAC");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CHAT_AUDIO_MIME.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("Unsupported audio content-type");
        }
    }
}