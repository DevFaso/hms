package com.example.hms.upload;

import java.util.EnumSet;
import java.util.Set;

import static com.example.hms.upload.UploadType.AAC;
import static com.example.hms.upload.UploadType.BMP;
import static com.example.hms.upload.UploadType.CSV;
import static com.example.hms.upload.UploadType.DOC;
import static com.example.hms.upload.UploadType.DOCX;
import static com.example.hms.upload.UploadType.GIF;
import static com.example.hms.upload.UploadType.JPEG;
import static com.example.hms.upload.UploadType.MP3;
import static com.example.hms.upload.UploadType.MP4_AUDIO;
import static com.example.hms.upload.UploadType.OGG_AUDIO;
import static com.example.hms.upload.UploadType.PDF;
import static com.example.hms.upload.UploadType.PNG;
import static com.example.hms.upload.UploadType.RTF;
import static com.example.hms.upload.UploadType.TEXT;
import static com.example.hms.upload.UploadType.TIFF;
import static com.example.hms.upload.UploadType.WEBM_AUDIO;
import static com.example.hms.upload.UploadType.WEBP;

/**
 * What each upload path accepts: the types (recognised by content), the size,
 * and the refusals it answers with. A multipart endpoint names its kind with
 * {@link UploadPolicy}; nothing is accepted on a path that names none.
 */
public enum UploadKind {
    PROFILE_IMAGE(megabytes(5), "file.image.tooLarge", "file.image.unsupported",
        EnumSet.of(JPEG, PNG, GIF, WEBP)),
    PATIENT_PHOTO(megabytes(5), "patient.photo.tooLarge", "patient.photo.typeUnsupported",
        EnumSet.of(JPEG, PNG, GIF, WEBP)),
    REFERRAL_ATTACHMENT(megabytes(10), "file.attachment.tooLarge", "file.attachment.unsupported",
        documents()),
    CHART_ATTACHMENT(megabytes(10), "file.attachment.tooLarge", "file.attachment.unsupported",
        documents()),
    PATIENT_DOCUMENT(megabytes(10), "file.document.tooLarge", "file.document.unsupported",
        documents()),
    /** The chat upload endpoint: a photo or a voice memo; the service narrows to the declared one. */
    CHAT_ATTACHMENT(megabytes(10), "file.photo.tooLarge", "file.attachment.extensionUnsupported",
        EnumSet.of(JPEG, PNG, WEBP, WEBM_AUDIO, OGG_AUDIO, MP4_AUDIO, MP3, AAC)),
    CHAT_PHOTO(megabytes(10), "file.photo.tooLarge", "file.photo.unsupported",
        EnumSet.of(JPEG, PNG, WEBP)),
    CHAT_AUDIO(megabytes(5), "file.voice.tooLarge", "file.audio.unsupported",
        EnumSet.of(WEBM_AUDIO, OGG_AUDIO, MP4_AUDIO, MP3, AAC)),
    IMAGING_IMAGE(megabytes(10), "imaging.image.tooLarge", "imaging.image.unsupported",
        EnumSet.of(JPEG, PNG, WEBP, PDF)),
    /** A CSV file read once by an import and never stored. */
    CSV_IMPORT(megabytes(10), "file.attachment.tooLarge", "file.upload.csvOnly",
        EnumSet.of(CSV, TEXT));

    private final long maxBytes;
    private final String tooLargeKey;
    private final String unsupportedKey;
    private final Set<UploadType> allowedTypes;

    UploadKind(long maxBytes, String tooLargeKey, String unsupportedKey, Set<UploadType> allowedTypes) {
        this.maxBytes = maxBytes;
        this.tooLargeKey = tooLargeKey;
        this.unsupportedKey = unsupportedKey;
        this.allowedTypes = Set.copyOf(allowedTypes);
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** The message key of the size refusal; it takes the size label where its text names one. */
    public String tooLargeKey() {
        return tooLargeKey;
    }

    public String unsupportedKey() {
        return unsupportedKey;
    }

    public Set<UploadType> allowedTypes() {
        return allowedTypes;
    }

    /** Whether a file under this extension could be of an accepted type at all. */
    public boolean acceptsExtension(String extension) {
        return allowedTypes.stream().anyMatch(type -> type.extensions().contains(extension));
    }

    private static Set<UploadType> documents() {
        return EnumSet.of(PDF, JPEG, PNG, GIF, BMP, TIFF, TEXT, RTF, DOC, DOCX);
    }

    private static long megabytes(int count) {
        return count * 1024L * 1024L;
    }
}
