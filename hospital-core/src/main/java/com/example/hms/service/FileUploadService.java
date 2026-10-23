package com.example.hms.service;

import com.example.hms.enums.ChatAttachmentKind;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.service.support.UrlPathNormalizer;
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
import java.nio.charset.StandardCharsets;
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
    private static final String MSG_FILE_PATH_INVALID = "file.path.invalid";
    private static final String MSG_FILE_EMPTY = "file.empty";
    private static final String MSG_ATTACHMENT_KEY_INVALID = "file.attachment.keyInvalid";
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
        patientDocumentUrlPrefix = UrlPathNormalizer.prefix(patientDocumentUrlPrefix, "/uploads");
    }

    /**
     * Public base URL for file links returned to the browser.
     * <p>
     * Set this to your public-facing domain, e.g.
     * {@code https://dev.e-keneya.com} so that the browser can reach the
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

    /**
     * The one upload limit, in bytes: {@code spring.servlet.multipart.max-file-size}
     * (10MB) rejects anything larger before this service runs, so a bigger number
     * here was a promise the container never kept (it said 20MB for months).
     * {@code UploadLimitConsistencyTest} fails if the two drift apart.
     */
    public static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;
    /** Shown in refusals; must describe {@link #MAX_UPLOAD_BYTES}. */
    public static final String MAX_UPLOAD_LABEL = "10 MB";
    private static final String EXT_JPEG = ".jpeg";
    private static final String EXT_TIFF = ".tiff";
    private static final String EXT_DOCX = ".docx";
    private static final String MIME_PNG = "image/png";
    private static final String MIME_JPEG = "image/jpeg";
    private static final long MAX_REFERRAL_ATTACHMENT_SIZE = MAX_UPLOAD_BYTES;
    private static final Set<String> ALLOWED_ATTACHMENT_EXTENSIONS = Set.of(
        ".pdf", ".jpg", EXT_JPEG, ".png", ".gif", ".bmp", EXT_TIFF, ".txt", ".rtf", ".doc",
        EXT_DOCX
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
        // Random suffix: profile images are the one class still served
        // statically (avatars render via <img src> on web + both mobile
        // apps, which cannot carry a bearer token), so the filename must
        // not be derivable from the public userId + a timestamp guess.
        String filename = userId + "_profile_" + System.currentTimeMillis()
            + "_" + UUID.randomUUID() + extension;

        Path filePath = uploadPath.resolve(filename).normalize();
        if (!filePath.startsWith(uploadPath)) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw(MSG_FILE_PATH_INVALID));
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
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw(MSG_FILE_PATH_INVALID));
        }
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        String relativePath = "/uploads/referral-attachments/" + filename;
        return new StoredFileDescriptor(
            relativePath,
            buildPublicUrl(relativePath),
            determineDisplayName(file, filename),
            contentTypeFor(extension),
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
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw(MSG_FILE_PATH_INVALID));
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
            contentTypeFor(extension),
            sizeBytes,
            checksum
        );
    }

    /**
     * Resolve a stored upload for AUTHENTICATED streaming (P3 follow-up:
     * closing the permitAll /uploads/** exposure). The storage key comes
     * from a DB row — never raw client input — and may be either the
     * relative form ("/uploads/{subdir}/{name}") or a legacy absolute
     * public URL; both resolve. The returned path is guaranteed to sit
     * inside the named subdirectory, so traversal has nothing to grab.
     *
     * @throws ResourceNotFoundException when the key is not under the
     *         required subdirectory or the file no longer exists on disk
     */
    public Path resolveStoredFile(String storageKey, String requiredSubdirectory) {
        String marker = "/uploads/" + requiredSubdirectory + "/";
        int idx = storageKey == null ? -1 : storageKey.indexOf(marker);
        if (idx < 0) {
            throw new ResourceNotFoundException("file.notStoredUnder", requiredSubdirectory);
        }
        String filename = storageKey.substring(idx + marker.length());
        Path base = Paths.get(uploadDir, requiredSubdirectory).toAbsolutePath().normalize();
        Path path = base.resolve(filename).normalize();
        if (!path.startsWith(base) || !Files.exists(path)) {
            throw new ResourceNotFoundException("file.noLongerAvailable");
        }
        return path;
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
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw(MSG_FILE_EMPTY));
        }

        // Check file size (max 5MB)
        long maxSize = 5L * 1024 * 1024; // 5MB
        if (file.getSize() > maxSize) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.image.tooLarge"));
        }

        // Check content type
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.image.required"));
        }

        // Check allowed extensions
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.name.required"));
        }

        String extension = getFileExtension(originalFilename).toLowerCase();
        if (!extension.matches("\\.(jpg|jpeg|png|gif|webp)$")) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.image.unsupported"));
        }
    }

    private void validateAttachmentFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw(MSG_FILE_EMPTY));
        }

        if (file.getSize() > MAX_REFERRAL_ATTACHMENT_SIZE) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.attachment.tooLarge", MAX_UPLOAD_LABEL));
        }

        String extension = getFileExtension(file.getOriginalFilename()).toLowerCase();
        if (!ALLOWED_ATTACHMENT_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.attachment.unsupported"));
        }
        requireContentMatchesExtension(file, extension);
    }

    /**
     * QA D540: the extension and the declared Content-Type are the client's
     * claim. The first bytes must be what the extension says (a PDF starts with
     * "%PDF-", a PNG with its signature...), and a text or RTF file must not be
     * markup a browser would run. HTML declared as a PDF is refused.
     */
    static void requireContentMatchesExtension(MultipartFile file, String extension) {
        byte[] head;
        try (java.io.InputStream in = file.getInputStream()) {
            head = in.readNBytes(512);
        } catch (IOException ex) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.unreadable"), ex);
        }
        if (!contentMatches(head, extension)) {
            throw new IllegalArgumentException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("file.contentMismatch", extension));
        }
    }

    static boolean contentMatches(byte[] head, String extension) {
        return switch (extension) {
            case ".pdf" -> startsWith(head, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case ".png" -> startsWith(head, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            case ".jpg", EXT_JPEG -> startsWith(head, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            case ".gif" -> startsWith(head, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(head, "GIF89a".getBytes(StandardCharsets.US_ASCII));
            case ".bmp" -> startsWith(head, "BM".getBytes(StandardCharsets.US_ASCII));
            case EXT_TIFF -> startsWith(head, new byte[] {'I', 'I', 0x2A, 0x00})
                || startsWith(head, new byte[] {'M', 'M', 0x00, 0x2A});
            case ".doc" -> startsWith(head, new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});
            case EXT_DOCX -> startsWith(head, new byte[] {'P', 'K', 0x03, 0x04});
            case ".rtf" -> startsWith(head, "{\\rtf".getBytes(StandardCharsets.US_ASCII));
            case ".txt" -> !looksLikeMarkup(head);
            default -> false;
        };
    }

    private static boolean startsWith(byte[] head, byte[] magic) {
        if (head.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (head[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeMarkup(byte[] head) {
        String text = new String(head, StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
        return text.contains("<html") || text.contains("<script") || text.contains("<!doctype")
            || text.contains("<svg") || text.contains("<iframe") || text.contains("<body");
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

        // Prefer the explicitly configured public URL (e.g. https://dev.e-keneya.com).
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

    private static final long MAX_PATIENT_DOCUMENT_SIZE = MAX_UPLOAD_BYTES;
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
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw(MSG_FILE_PATH_INVALID));
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
                contentTypeFor(extension),
                sizeBytes,
                checksum
        );
    }

    private void validatePatientDocumentFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw(MSG_FILE_EMPTY));
        }

        if (file.getSize() > MAX_PATIENT_DOCUMENT_SIZE) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.document.tooLarge", MAX_UPLOAD_LABEL));
        }

        String extension = getFileExtension(file.getOriginalFilename()).toLowerCase();
        if (!ALLOWED_ATTACHMENT_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.document.unsupported"));
        }
        requireContentMatchesExtension(file, extension);
    }

    /** QA D540: the stored type follows the checked extension, not the client's header. */
    static String contentTypeFor(String extension) {
        return switch (extension.toLowerCase(java.util.Locale.ROOT)) {
            case ".pdf" -> "application/pdf";
            case ".png" -> MIME_PNG;
            case ".jpg", EXT_JPEG -> MIME_JPEG;
            case ".gif" -> "image/gif";
            case ".bmp" -> "image/bmp";
            case EXT_TIFF -> "image/tiff";
            case ".txt" -> "text/plain";
            case ".rtf" -> "application/rtf";
            case ".doc" -> "application/msword";
            case EXT_DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> "application/octet-stream";
        };
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
        ".jpg", EXT_JPEG, ".png", ".webp"
    );
    private static final Set<String> ALLOWED_CHAT_PHOTO_MIME = Set.of(
        MIME_JPEG, MIME_PNG, "image/webp"
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
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw(MSG_FILE_PATH_INVALID));
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
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.attachment.keyRequired"));
        }
        if (declaredKind == null) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.attachment.kindRequired"));
        }
        if (!storageKey.startsWith(CHAT_ATTACHMENT_KEY_PREFIX)) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw(MSG_ATTACHMENT_KEY_INVALID));
        }
        String filename = storageKey.substring(CHAT_ATTACHMENT_KEY_PREFIX.length());
        if (filename.isBlank() || filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw(MSG_ATTACHMENT_KEY_INVALID));
        }
        Path uploadPath = Paths.get(uploadDir, CHAT_ATTACHMENTS_PATH).toAbsolutePath().normalize();
        Path filePath = uploadPath.resolve(filename).normalize();
        if (!filePath.startsWith(uploadPath)) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw(MSG_ATTACHMENT_KEY_INVALID));
        }
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.attachment.notFound"));
        }

        String extension = getFileExtension(filename).toLowerCase();
        ChatAttachmentKind derivedKind = deriveChatAttachmentKindFromExtension(extension);
        if (derivedKind == null) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.attachment.extensionUnsupported"));
        }
        if (derivedKind != declaredKind) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.attachment.kindMismatch"));
        }

        long sizeBytes = Files.size(filePath);
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.attachment.empty"));
        }
        if (declaredKind == ChatAttachmentKind.PHOTO && sizeBytes > MAX_CHAT_PHOTO_SIZE) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.photo.tooLarge"));
        }
        if (declaredKind == ChatAttachmentKind.AUDIO && sizeBytes > MAX_CHAT_AUDIO_SIZE) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.voice.tooLarge"));
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
            case ".jpg", EXT_JPEG -> MIME_JPEG;
            case ".png" -> MIME_PNG;
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
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.attachment.kindRequired"));
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw(MSG_FILE_EMPTY));
        }
        if (kind == ChatAttachmentKind.AUDIO) {
            validateChatAudio(file);
        } else {
            validateChatPhoto(file);
        }
    }

    private void validateChatPhoto(MultipartFile file) {
        if (file.getSize() > MAX_CHAT_PHOTO_SIZE) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.photo.tooLarge"));
        }
        String extension = getFileExtension(file.getOriginalFilename()).toLowerCase();
        if (!ALLOWED_CHAT_PHOTO_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.photo.unsupported"));
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CHAT_PHOTO_MIME.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.photo.contentTypeUnsupported"));
        }
    }

    private void validateChatAudio(MultipartFile file) {
        if (file.getSize() > MAX_CHAT_AUDIO_SIZE) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.voice.tooLarge"));
        }
        String extension = getFileExtension(file.getOriginalFilename()).toLowerCase();
        if (!ALLOWED_CHAT_AUDIO_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.audio.unsupported"));
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CHAT_AUDIO_MIME.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.audio.contentTypeUnsupported"));
        }
    }
}