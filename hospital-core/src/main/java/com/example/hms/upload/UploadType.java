package com.example.hms.upload;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The file types an upload may be, each recognised from the file's own bytes
 * ({@link ContentSniffer}). The media type a file is stored and served with is
 * the one named here, never the one the client declared.
 */
public enum UploadType {
    PDF("application/pdf", ".pdf"),
    PNG("image/png", ".png"),
    JPEG("image/jpeg", ".jpg", ".jpeg"),
    GIF("image/gif", ".gif"),
    BMP("image/bmp", ".bmp"),
    TIFF("image/tiff", ".tiff"),
    WEBP("image/webp", ".webp"),
    DOC("application/msword", ".doc"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
    RTF("application/rtf", ".rtf"),
    TEXT("text/plain", ".txt"),
    CSV("text/csv", ".csv"),
    WEBM_AUDIO("audio/webm", ".webm"),
    OGG_AUDIO("audio/ogg", ".ogg", ".oga"),
    MP4_AUDIO("audio/mp4", ".m4a", ".mp4"),
    MP3("audio/mpeg", ".mp3"),
    AAC("audio/aac", ".aac");

    private final String mediaType;
    private final String extension;
    private final Set<String> extensions;

    UploadType(String mediaType, String... extensions) {
        this.mediaType = mediaType;
        this.extension = extensions[0];
        this.extensions = Set.of(extensions);
    }

    /** The extension a file of this type is stored under when its own name announces another. */
    public String extension() {
        return extension;
    }

    /** The media type a file of this type is stored and served with. */
    public String mediaType() {
        return mediaType;
    }

    /** The file extensions (lower case, with the dot) a file of this type may carry. */
    public Set<String> extensions() {
        return extensions;
    }

    public boolean isRasterImage() {
        return this == PNG || this == JPEG || this == GIF || this == WEBP;
    }

    /** The raster images and the PDF: what a vetted viewer path may show in the page. */
    public boolean isInlineViewable() {
        return this == PDF || isRasterImage();
    }

    /** The type served under a stored media type; empty for anything this list does not name. */
    public static Optional<UploadType> ofMediaType(String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        int parameters = stored.indexOf(';');
        String bare = (parameters < 0 ? stored : stored.substring(0, parameters)).trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(type -> type.mediaType.equals(bare)).findFirst();
    }
}
