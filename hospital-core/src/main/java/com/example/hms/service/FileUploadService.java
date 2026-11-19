package com.example.hms.service;

import com.example.hms.enums.ChatAttachmentKind;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.service.support.UrlPathNormalizer;
import com.example.hms.storage.FileStorage;
import com.example.hms.storage.StorageKeys;
import com.example.hms.storage.StoredObject;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Validates and stores uploaded files (profile images, referral/chart/chat
 * attachments, patient documents) through {@link FileStorage}: a local
 * directory or an S3-compatible bucket, chosen by {@code app.storage.type}.
 * The keys are the paths the files always had under the upload root
 * ({@code "<subdirectory>/<name>"}), so the references stored on existing
 * rows keep resolving whichever store is used.
 */
@Slf4j
@Service
public class FileUploadService {
    private static final String MSG_FILE_PATH_INVALID = "file.path.invalid";
    private static final String MSG_FILE_EMPTY = "file.empty";
    private static final String MSG_ATTACHMENT_KEY_INVALID = "file.attachment.keyInvalid";
    public static final String PROFILE_IMAGES_PATH = "profile-images";
    private static final String MSG_FILE_NO_LONGER_AVAILABLE = "file.noLongerAvailable";

    private final FileStorage storage;

    public FileUploadService(FileStorage storage) {
        this.storage = storage;
    }

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
    private static final String MIME_PDF = "application/pdf";
    private static final String MIME_WEBP = "image/webp";
    private static final String EXT_WEBP = ".webp";
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

        String originalFilename = file.getOriginalFilename();
        String extension = getFileExtension(originalFilename);
        // Random suffix: profile images are the one class still served
        // without a token (avatars render via <img src> on web + both mobile
        // apps, which cannot carry a bearer token), so the filename must
        // not be derivable from the public userId + a timestamp guess.
        String filename = userId + "_profile_" + System.currentTimeMillis()
            + "_" + UUID.randomUUID() + extension;

        try (InputStream in = file.getInputStream()) {
            storage.put(storageKey(PROFILE_IMAGES_PATH, filename), in, file.getSize(),
                imageContentTypeFor(extension));
        }

        log.info("Profile image uploaded successfully for user {}", userId);

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

        // Extract filename from URL (e.g., "/api/uploads/profile-images/filename.jpg"
        // -> "filename.jpg")
        String filename = imageUrl.substring(imageUrl.lastIndexOf('/') + 1);
        String key;
        try {
            key = storageKey(PROFILE_IMAGES_PATH, filename);
        } catch (IllegalArgumentException invalid) {
            log.warn("Path traversal attempt detected in deleteProfileImage: {}", imageUrl);
            return;
        }
        try {
            storage.delete(key);
            log.info("Profile image deleted: {}", filename);
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to delete profile image: {}", imageUrl, e);
        }
    }

    public StoredFileDescriptor uploadReferralAttachment(MultipartFile file, UUID uploaderId) throws IOException {
        validateAttachmentFile(file);

        String sanitizedBaseName = sanitizeBaseFilename(file.getOriginalFilename());
        String extension = getFileExtension(file.getOriginalFilename());
        String filename = buildAttachmentFilename(sanitizedBaseName, extension, uploaderId, "attachment");

        storeUpload("referral-attachments", filename, file, contentTypeFor(extension));

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

        String sanitizedBaseName = sanitizeBaseFilename(file.getOriginalFilename());
        String extension = getFileExtension(file.getOriginalFilename());
        String filename = buildAttachmentFilename(sanitizedBaseName, extension, uploaderId, "chart_attachment");

        String checksum = storeUpload("chart-attachments", filename, file, contentTypeFor(extension));

        String relativePath = "/uploads/chart-attachments/" + filename;
        long sizeBytes = file.getSize();

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
     * Open a stored upload for AUTHENTICATED streaming (P3 follow-up:
     * closing the permitAll /uploads/** exposure). The storage key comes
     * from a DB row, never raw client input, and may be either the
     * relative form ("/uploads/{subdir}/{name}") or a legacy absolute
     * public URL; both resolve. The object read is always inside the named
     * subdirectory ({@link StorageKeys}), so traversal has nothing to grab.
     * The caller closes the returned object.
     *
     * @throws ResourceNotFoundException when the key is not under the
     *         required subdirectory or the file is no longer stored
     */
    public StoredObject openStoredFile(String storageKey, String requiredSubdirectory) {
        String key = keyOfStoredReference(storageKey, requiredSubdirectory);
        Optional<StoredObject> stored;
        try {
            stored = storage.get(key);
        } catch (IOException ex) {
            log.warn("Stored file under '{}' could not be read: {}", requiredSubdirectory, ex.getMessage());
            throw new ResourceNotFoundException(MSG_FILE_NO_LONGER_AVAILABLE);
        }
        if (stored.isEmpty()) {
            // QA D1184 (round 22): the row says a file was stored and the store
            // has none. The reader gets the localized 404; the log says which
            // store and subdirectory, never the name.
            log.warn("Stored file missing under '{}' in {}", requiredSubdirectory, storage.describe());
            throw new ResourceNotFoundException(MSG_FILE_NO_LONGER_AVAILABLE);
        }
        return stored.get();
    }

    /**
     * Remove a stored upload named by a DB reference (same forms as
     * {@link #openStoredFile}); nothing happens when it is already gone.
     */
    public void deleteStoredFile(String storageKey, String requiredSubdirectory) throws IOException {
        storage.delete(keyOfStoredReference(storageKey, requiredSubdirectory));
    }

    /** The storage key of a stored reference ("/uploads/{subdir}/{name}" or a URL ending so). */
    private static String keyOfStoredReference(String storageKey, String requiredSubdirectory) {
        String marker = "/uploads/" + requiredSubdirectory + "/";
        int idx = storageKey == null ? -1 : storageKey.indexOf(marker);
        if (idx < 0) {
            throw new ResourceNotFoundException("file.notStoredUnder", requiredSubdirectory);
        }
        String filename = storageKey.substring(idx + marker.length());
        try {
            return storageKey(requiredSubdirectory, filename);
        } catch (IllegalArgumentException invalid) {
            throw new ResourceNotFoundException(MSG_FILE_NO_LONGER_AVAILABLE);
        }
    }

    /** {@code subdir + "/" + filename}, checked by {@link StorageKeys}. */
    private static String storageKey(String subdirectory, String filename) {
        return StorageKeys.requireValid(subdirectory + "/" + filename);
    }

    /**
     * Stream an upload into the store under {@code subdir/filename}, hashing
     * it on the way through; returns the SHA-256 (hex).
     */
    private String storeUpload(String subdirectory, String filename, MultipartFile file, String contentType)
            throws IOException {
        String key;
        try {
            key = storageKey(subdirectory, filename);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw(MSG_FILE_PATH_INVALID));
        }
        MessageDigest digest = createSha256Digest();
        try (InputStream in = file.getInputStream();
             DigestInputStream digestStream = new DigestInputStream(in, digest)) {
            storage.put(key, digestStream, file.getSize(), contentType);
        }
        return HexFormat.of().formatHex(digest.digest());
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
            // RIFF container, then "WEBP" at offset 8.
            case EXT_WEBP -> startsWith(head, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && head.length >= 12 && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
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
     * @throws IOException if the file cannot be stored
     */
    public StoredFileDescriptor uploadPatientDocument(MultipartFile file, UUID userId) throws IOException {
        validatePatientDocumentFile(file);

        String sanitizedBaseName = sanitizeBaseFilename(file.getOriginalFilename());
        String extension = getFileExtension(file.getOriginalFilename());
        String filename = buildAttachmentFilename(sanitizedBaseName, extension, userId, "patient_doc");

        String checksum = storeUpload(PATIENT_DOCUMENTS_PATH, filename, file, contentTypeFor(extension));

        String relativePath = patientDocumentRelativePath(filename);
        long sizeBytes = file.getSize();

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
     * QA D961: store a document the server generated (a medical certificate
     * PDF) beside the patient's documents, under a server-built name, so the
     * staff chart and the patient portal list and download it like any other.
     */
    public StoredFileDescriptor storeGeneratedPatientDocument(byte[] content, String baseName, UUID authorId)
            throws IOException {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw(MSG_FILE_EMPTY));
        }
        String filename = buildAttachmentFilename(sanitizeBaseFilename(baseName), ".pdf", authorId, "certificate");
        storage.put(storageKey(PATIENT_DOCUMENTS_PATH, filename), new ByteArrayInputStream(content),
            content.length, MIME_PDF);
        MessageDigest digest = createSha256Digest();
        String checksum = HexFormat.of().formatHex(digest.digest(content));
        String relativePath = patientDocumentRelativePath(filename);
        return new StoredFileDescriptor(relativePath, buildPublicUrl(relativePath), baseName + ".pdf",
            MIME_PDF, content.length, checksum);
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
            case ".pdf" -> MIME_PDF;
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

    /**
     * The type a profile image is stored and served with, from its checked
     * extension ({@code .jpg .jpeg .png .gif .webp}); {@code null} for anything else.
     */
    public static String imageContentTypeFor(String extension) {
        return switch (extension.toLowerCase(java.util.Locale.ROOT)) {
            case ".jpg", EXT_JPEG -> MIME_JPEG;
            case ".png" -> MIME_PNG;
            case ".gif" -> "image/gif";
            case EXT_WEBP -> MIME_WEBP;
            default -> null;
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
        ".jpg", EXT_JPEG, ".png", EXT_WEBP
    );
    private static final Set<String> ALLOWED_CHAT_PHOTO_MIME = Set.of(
        MIME_JPEG, MIME_PNG, MIME_WEBP
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

        String sanitizedBaseName = sanitizeBaseFilename(file.getOriginalFilename());
        String extension = getFileExtension(file.getOriginalFilename());
        String filename = buildAttachmentFilename(sanitizedBaseName, extension, uploaderId,
            kind == ChatAttachmentKind.AUDIO ? "voice_memo" : "photo");

        String checksum = storeUpload(CHAT_ATTACHMENTS_PATH, filename, file, file.getContentType());

        String relativePath = relativeUploadPath(CHAT_ATTACHMENTS_PATH, filename);
        long sizeBytes = file.getSize();

        return new StoredFileDescriptor(
            relativePath,
            buildPublicUrl(relativePath),
            determineDisplayName(file, filename),
            file.getContentType(),
            sizeBytes,
            checksum
        );
    }

    /** The stored key of a patient document: {@code <prefix>/patient-documents/<filename>}. */
    private String patientDocumentRelativePath(String filename) {
        return String.join("/", patientDocumentUrlPrefix, PATIENT_DOCUMENTS_PATH, filename);
    }

    private static String relativeUploadPath(String subdir, String filename) {
        return String.join("/", "/uploads", subdir, filename);
    }

    private static final String CHAT_ATTACHMENT_KEY_PREFIX = "/uploads/chat-attachments/";

    /**
     * Re-derive a server-trusted descriptor from a previously uploaded chat-attachment storage
     * key. The caller (chat send path) only supplies the storage key + declared kind +
     * (for AUDIO) the client-measured duration; everything else (size, sha256, content type,
     * display name, public url) is recomputed from the stored bytes so a tampered or omitted client
     * descriptor cannot poison the persisted row.
     *
     * @throws IllegalArgumentException if the key is malformed, the file is missing, or kind
     *     mismatches the stored file's type
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
        String key;
        try {
            key = storageKey(CHAT_ATTACHMENTS_PATH, filename);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw(MSG_ATTACHMENT_KEY_INVALID));
        }

        String extension = getFileExtension(filename).toLowerCase();
        ChatAttachmentKind derivedKind = deriveChatAttachmentKindFromExtension(extension);
        if (derivedKind == null) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.attachment.extensionUnsupported"));
        }
        if (derivedKind != declaredKind) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.attachment.kindMismatch"));
        }

        Optional<StoredObject> stored = storage.get(key);
        if (stored.isEmpty()) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.attachment.notFound"));
        }
        MessageDigest digest = createSha256Digest();
        long sizeBytes = 0;
        try (StoredObject object = stored.get();
             DigestInputStream digestStream = new DigestInputStream(object.content(), digest)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = digestStream.read(buf)) != -1) {
                sizeBytes += read;
            }
        }
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
        String sha256 = HexFormat.of().formatHex(digest.digest());
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
            case EXT_WEBP -> MIME_WEBP;
            case ".webm" -> "audio/webm";
            case ".ogg", ".oga" -> "audio/ogg";
            case ".m4a", ".mp4" -> "audio/mp4";
            case ".mp3" -> "audio/mpeg";
            case ".aac" -> "audio/aac";
            default -> "application/octet-stream";
        };
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

    // ---------------------------------------------------------------------
    // V316 (owner 2026-11-19): key images on an imaging study. JPEG, PNG,
    // WebP or a machine-exported PDF, content-sniffed, the same 10 MB limit
    // as every other upload. Stored under a SERVER-BUILT name (a random
    // UUID and the checked extension): the uploader's file name can carry
    // the patient's name and never reaches the key. The prefix has no
    // static mapping; the bytes stream only through the authenticated
    // imaging image endpoint.
    // ---------------------------------------------------------------------
    public static final String IMAGING_IMAGES_PATH = "imaging-images";
    private static final Set<String> ALLOWED_IMAGING_IMAGE_EXTENSIONS = Set.of(
        ".jpg", EXT_JPEG, ".png", EXT_WEBP, ".pdf");

    /**
     * Validate and store one imaging key image.
     *
     * @return the descriptor; its {@code storageKey} is the
     *         {@code "/uploads/imaging-images/<uuid>.<ext>"} reference that
     *         {@link #openStoredFile} and {@link #deleteStoredFile} read back
     */
    public StoredFileDescriptor uploadImagingImage(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw(MSG_FILE_EMPTY));
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("imaging.image.tooLarge", MAX_UPLOAD_LABEL));
        }
        String extension = getFileExtension(file.getOriginalFilename()).toLowerCase(java.util.Locale.ROOT);
        if (!ALLOWED_IMAGING_IMAGE_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("imaging.image.unsupported"));
        }
        requireContentMatchesExtension(file, extension);
        String contentType = imagingImageContentTypeFor(extension);
        String filename = UUID.randomUUID() + (EXT_JPEG.equals(extension) ? ".jpg" : extension);
        String checksum = storeUpload(IMAGING_IMAGES_PATH, filename, file, contentType);
        return new StoredFileDescriptor(relativeUploadPath(IMAGING_IMAGES_PATH, filename), null,
            null, contentType, file.getSize(), checksum);
    }

    /** The type an imaging key image is stored and served with, from its checked extension. */
    static String imagingImageContentTypeFor(String extension) {
        return switch (extension.toLowerCase(java.util.Locale.ROOT)) {
            case ".jpg", EXT_JPEG -> MIME_JPEG;
            case ".png" -> MIME_PNG;
            case EXT_WEBP -> MIME_WEBP;
            case ".pdf" -> MIME_PDF;
            default -> throw new IllegalArgumentException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("imaging.image.unsupported"));
        };
    }
}
