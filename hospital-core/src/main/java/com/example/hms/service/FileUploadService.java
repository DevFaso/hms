package com.example.hms.service;

import com.example.hms.enums.ChatAttachmentKind;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.service.support.UrlPathNormalizer;
import com.example.hms.storage.FileStorage;
import com.example.hms.storage.StorageKeys;
import com.example.hms.storage.StoredObject;
import com.example.hms.upload.UploadAdmission;
import com.example.hms.upload.UploadAdmission.VettedUpload;
import com.example.hms.upload.UploadKind;
import com.example.hms.upload.UploadLedger;
import com.example.hms.upload.UploadRefusedException;
import com.example.hms.upload.UploadScanStatus;
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
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;

/**
 * Validates and stores uploaded files (profile images, referral/chart/chat
 * attachments, patient documents) through {@link FileStorage}: a local
 * directory or an S3-compatible bucket, chosen by {@code app.storage.type}.
 * The keys are the paths the files always had under the upload root
 * ({@code "<subdirectory>/<name>"}), so the references stored on existing
 * rows keep resolving whichever store is used.
 *
 * <p>Every file is checked by {@link UploadAdmission} (size, type by content,
 * name matching content) and stored with the type its content showed; each
 * stored file is recorded in the {@link UploadLedger}, which holds the state
 * of its malware check, and a file whose check is not clean is not opened.
 */
@Slf4j
@Service
public class FileUploadService {
    private static final String MSG_FILE_PATH_INVALID = "file.path.invalid";
    private static final String MSG_FILE_EMPTY = "file.empty";
    private static final String MSG_ATTACHMENT_KEY_INVALID = "file.attachment.keyInvalid";
    public static final String PROFILE_IMAGES_PATH = "profile-images";
    private static final String MSG_FILE_NO_LONGER_AVAILABLE = "file.noLongerAvailable";

    private static final String MSG_UPLOAD_BLOCKED = "file.upload.blocked";

    private final FileStorage storage;
    /** Null only in a unit test that builds the service on a bare store. */
    private final UploadLedger ledger;

    @org.springframework.beans.factory.annotation.Autowired
    public FileUploadService(FileStorage storage, UploadLedger ledger) {
        this.storage = storage;
        this.ledger = ledger;
    }

    /** For tests of the storage paths alone: no upload record, no malware check. */
    FileUploadService(FileStorage storage) {
        this(storage, null);
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

    /**
     * QA D2465 (r64): {@link #MAX_UPLOAD_LABEL} in the reader's language
     * (« 10 Mo » in French): every refusal names the limit through this, never
     * the English constant. Falls back to the constant without a bundle.
     */
    public static String maxUploadLabel(java.util.Locale locale) {
        String label = com.example.hms.utility.MessageUtil.resolveOrNull(locale, "upload.limit.label");
        return label != null ? label : MAX_UPLOAD_LABEL;
    }

    /** {@link #maxUploadLabel(java.util.Locale)} in the caller's locale. */
    public static String maxUploadLabel() {
        return maxUploadLabel(org.springframework.context.i18n.LocaleContextHolder.getLocale());
    }
    private static final String EXT_JPEG = ".jpeg";
    private static final String MIME_PNG = "image/png";
    private static final String MIME_JPEG = "image/jpeg";
    private static final String MIME_PDF = "application/pdf";
    private static final String MIME_WEBP = "image/webp";
    private static final String EXT_WEBP = ".webp";

    /**
     * Upload a profile image for a user
     * 
     * @param file   The uploaded file
     * @param userId The user ID
     * @return The URL path to access the uploaded file
     * @throws IOException if upload fails
     */
    public String uploadProfileImage(MultipartFile file, UUID userId) throws IOException {
        VettedUpload vetted = vet(file, UploadKind.PROFILE_IMAGE);
        String extension = vetted.extension();
        // Random suffix: profile images are the one class still served
        // without a token (avatars render via <img src> on web + both mobile
        // apps, which cannot carry a bearer token), so the filename must
        // not be derivable from the public userId + a timestamp guess.
        String filename = userId + "_profile_" + System.currentTimeMillis()
            + "_" + UUID.randomUUID() + extension;

        storeUpload(PROFILE_IMAGES_PATH, filename, file, vetted, UploadKind.PROFILE_IMAGE, userId);

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
        VettedUpload vetted = vet(file, UploadKind.REFERRAL_ATTACHMENT);

        String sanitizedBaseName = sanitizeBaseFilename(file.getOriginalFilename());
        String filename = buildAttachmentFilename(sanitizedBaseName, vetted.extension(), uploaderId, "attachment");

        storeUpload("referral-attachments", filename, file, vetted, UploadKind.REFERRAL_ATTACHMENT, uploaderId);

        String relativePath = "/uploads/referral-attachments/" + filename;
        return new StoredFileDescriptor(
            relativePath,
            buildPublicUrl(relativePath),
            determineDisplayName(file, filename),
            vetted.mediaType(),
            file.getSize(),
            null
        );
    }

    public StoredFileDescriptor uploadChartAttachment(MultipartFile file, UUID uploaderId) throws IOException {
        VettedUpload vetted = vet(file, UploadKind.CHART_ATTACHMENT);

        String sanitizedBaseName = sanitizeBaseFilename(file.getOriginalFilename());
        String filename = buildAttachmentFilename(sanitizedBaseName, vetted.extension(), uploaderId,
            "chart_attachment");

        String checksum = storeUpload("chart-attachments", filename, file, vetted, UploadKind.CHART_ATTACHMENT,
            uploaderId);

        String relativePath = "/uploads/chart-attachments/" + filename;
        long sizeBytes = file.getSize();

        return new StoredFileDescriptor(
            relativePath,
            buildPublicUrl(relativePath),
            determineDisplayName(file, filename),
            vetted.mediaType(),
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
     *         required subdirectory, the file is no longer stored, or its
     *         malware check is pending or refused it
     */
    public StoredObject openStoredFile(String storageKey, String requiredSubdirectory) {
        String key = keyOfStoredReference(storageKey, requiredSubdirectory);
        if (ledger != null) {
            ledger.requireServable(key);
        }
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
     * The shared check of an upload ({@link UploadAdmission}); its refusal
     * reaches the caller as the {@code IllegalArgumentException} the upload
     * endpoints answer with a 400.
     */
    private static VettedUpload vet(MultipartFile file, UploadKind kind) {
        try {
            return UploadAdmission.vet(file, kind);
        } catch (UploadRefusedException refused) {
            throw new IllegalArgumentException(refused.getMessage(), refused);
        }
    }

    /**
     * Stream a checked upload into the store under {@code subdir/filename}
     * with the type its content showed, hashing it on the way through, then
     * record it (and its malware check); returns the SHA-256 (hex).
     *
     * @throws IllegalArgumentException when the malware check refused the
     *         file: it is kept, unserved, and the upload is not linked
     */
    private String storeUpload(String subdirectory, String filename, MultipartFile file, VettedUpload vetted,
                               UploadKind kind, UUID uploaderId) throws IOException {
        String contentType = vetted.mediaType();
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
        String sha256 = HexFormat.of().formatHex(digest.digest());
        if (ledger != null) {
            UploadScanStatus status = ledger.record(new UploadLedger.StoredUpload(
                key, kind, contentType, file.getSize(), sha256, uploaderId, null));
            if (status == UploadScanStatus.INFECTED) {
                throw new IllegalArgumentException(
                    com.example.hms.utility.MessageUtil.resolveOrRaw(MSG_UPLOAD_BLOCKED));
            }
        }
        return sha256;
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
        VettedUpload vetted = vet(file, UploadKind.PATIENT_DOCUMENT);

        String sanitizedBaseName = sanitizeBaseFilename(file.getOriginalFilename());
        String filename = buildAttachmentFilename(sanitizedBaseName, vetted.extension(), userId, "patient_doc");

        String checksum = storeUpload(PATIENT_DOCUMENTS_PATH, filename, file, vetted, UploadKind.PATIENT_DOCUMENT,
            userId);

        String relativePath = patientDocumentRelativePath(filename);
        long sizeBytes = file.getSize();

        return new StoredFileDescriptor(
                relativePath,
                buildPublicUrl(relativePath),
                determineDisplayName(file, filename),
                vetted.mediaType(),
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
        if (kind == null) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("file.attachment.kindRequired"));
        }
        UploadKind uploadKind = kind == ChatAttachmentKind.AUDIO ? UploadKind.CHAT_AUDIO : UploadKind.CHAT_PHOTO;
        VettedUpload vetted = vet(file, uploadKind);

        String sanitizedBaseName = sanitizeBaseFilename(file.getOriginalFilename());
        String filename = buildAttachmentFilename(sanitizedBaseName, vetted.extension(), uploaderId,
            kind == ChatAttachmentKind.AUDIO ? "voice_memo" : "photo");

        String checksum = storeUpload(CHAT_ATTACHMENTS_PATH, filename, file, vetted, uploadKind, uploaderId);

        String relativePath = relativeUploadPath(CHAT_ATTACHMENTS_PATH, filename);
        long sizeBytes = file.getSize();

        return new StoredFileDescriptor(
            relativePath,
            buildPublicUrl(relativePath),
            determineDisplayName(file, filename),
            vetted.mediaType(),
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

        if (ledger != null) {
            // A file whose malware check is pending or refused it is not linked to a message.
            ledger.requireServable(key);
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
        if (UploadKind.CHAT_PHOTO.acceptsExtension(extension)) return ChatAttachmentKind.PHOTO;
        if (UploadKind.CHAT_AUDIO.acceptsExtension(extension)) return ChatAttachmentKind.AUDIO;
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

    /**
     * Validate and store one imaging key image.
     *
     * @return the descriptor; its {@code storageKey} is the
     *         {@code "/uploads/imaging-images/<uuid>.<ext>"} reference that
     *         {@link #openStoredFile} and {@link #deleteStoredFile} read back
     */
    public StoredFileDescriptor uploadImagingImage(MultipartFile file) throws IOException {
        VettedUpload vetted = vet(file, UploadKind.IMAGING_IMAGE);
        String extension = vetted.extension();
        String filename = UUID.randomUUID() + (EXT_JPEG.equals(extension) ? ".jpg" : extension);
        String checksum = storeUpload(IMAGING_IMAGES_PATH, filename, file, vetted, UploadKind.IMAGING_IMAGE, null);
        return new StoredFileDescriptor(relativeUploadPath(IMAGING_IMAGES_PATH, filename), null,
            null, vetted.mediaType(), file.getSize(), checksum);
    }
}
