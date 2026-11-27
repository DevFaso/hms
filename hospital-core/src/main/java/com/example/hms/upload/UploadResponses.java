package com.example.hms.upload;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The headers every stored file leaves with: the media type its content
 * showed when it was stored, {@code X-Content-Type-Options: nosniff}, and a
 * {@code Content-Disposition}. A file is an attachment unless the path is a
 * vetted viewer ({@link #viewer}) and the type is a raster image or a PDF.
 * A stored type this application does not store (an old row holding a type a
 * client once declared) leaves as {@code application/octet-stream}: nothing a
 * browser would render or run is ever served in the page.
 *
 * <p>{@code UploadServingGuardTest} fails the build when a controller streams
 * a stored file without going through here.
 */
public final class UploadResponses {

    public static final String NOSNIFF_HEADER = "X-Content-Type-Options";
    public static final String NOSNIFF = "nosniff";

    private UploadResponses() {
    }

    /** A stored file as a download, whatever its type. */
    public static ResponseEntity.BodyBuilder attachment(String storedType, String fileName) {
        return respond(UploadType.ofMediaType(storedType), fileName, false);
    }

    /**
     * A stored file on a path whose viewer shows it in the page: inline for a
     * raster image or a PDF, a download for everything else.
     */
    public static ResponseEntity.BodyBuilder viewer(String storedType, String fileName) {
        return respond(UploadType.ofMediaType(storedType), fileName, true);
    }

    /** The media type a stored type is served with. */
    public static MediaType servedTypeOf(String storedType) {
        return UploadType.ofMediaType(storedType)
            .map(type -> MediaType.parseMediaType(type.mediaType()))
            .orElse(MediaType.APPLICATION_OCTET_STREAM);
    }

    private static ResponseEntity.BodyBuilder respond(Optional<UploadType> type, String fileName, boolean viewer) {
        boolean inline = viewer && type.isPresent() && type.get().isInlineViewable();
        ContentDisposition.Builder disposition = inline ? ContentDisposition.inline() : ContentDisposition.attachment();
        String name = fileName == null || fileName.isBlank() ? "file" : fileName.replaceAll("\\p{Cntrl}", "_");
        // RFC 5987 form (filename*=UTF-8''...) for a name outside ASCII; the builder escapes
        // quotes and backslashes: a patient's own file name cannot break the header.
        disposition = StandardCharsets.US_ASCII.newEncoder().canEncode(name)
            ? disposition.filename(name) : disposition.filename(name, StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .contentType(type.map(known -> MediaType.parseMediaType(known.mediaType()))
                .orElse(MediaType.APPLICATION_OCTET_STREAM))
            .header(NOSNIFF_HEADER, NOSNIFF)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.build().toString());
    }
}
