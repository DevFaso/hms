package com.example.hms.upload;

import com.example.hms.service.FileUploadService;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The one check every uploaded file passes, whatever the path: its size, the
 * type its own bytes show, and that this type is one the path accepts and the
 * one its name announces. The client's Content-Type and extension decide
 * nothing; a file that is HTML, SVG or script is refused everywhere.
 *
 * <p>{@link UploadAdmissionInterceptor} runs it for every multipart request
 * before the handler; the services that store a file run it again for the
 * type they store (answered from the request when the interceptor already
 * checked that file).
 */
public final class UploadAdmission {

    private static final String ATTRIBUTE = UploadAdmission.class.getName() + ".vetted";

    private UploadAdmission() {
    }

    /** A file that passed: the type its content showed, and the extension to store it under. */
    public record VettedUpload(UploadType type, String extension, long sizeBytes) {
        /** The media type to store and serve the file with. */
        public String mediaType() {
            return type.mediaType();
        }
    }

    /**
     * Check one file against a kind.
     *
     * @throws UploadRefusedException with the refusal in the caller's language
     */
    public static VettedUpload vet(MultipartFile file, UploadKind kind) {
        if (file == null || file.isEmpty()) {
            throw new UploadRefusedException("file.empty");
        }
        Map<UploadKind, VettedUpload> known = knownFor(file);
        VettedUpload vetted = known != null ? known.get(kind) : null;
        if (vetted == null) {
            vetted = check(file, kind);
            if (known != null) {
                known.put(kind, vetted);
            }
        }
        return vetted;
    }

    private static VettedUpload check(MultipartFile file, UploadKind kind) {
        if (file.getSize() > kind.maxBytes()) {
            throw new UploadRefusedException(kind.tooLargeKey(), FileUploadService.maxUploadLabel());
        }
        String named = extensionOf(file.getOriginalFilename());
        if (kind.takesPhotosByBytes()) {
            Optional<VettedUpload> photo = photoByBytes(file, kind, named);
            if (photo.isPresent()) {
                return photo.get();
            }
        }
        String extension = named;
        // The name first: a file no accepted type is ever named like is not this path's.
        if (!kind.acceptsExtension(extension)) {
            throw notAccepted(kind);
        }
        // Then the bytes: an accepted type, and the one the name announces.
        Optional<UploadType> detected = ContentSniffer.detect(headOf(file), extension)
            .filter(kind.allowedTypes()::contains)
            .filter(shown -> shown.extensions().contains(extension));
        if (detected.isEmpty()) {
            throw new UploadRefusedException("file.contentMismatch", extension);
        }
        UploadType type = detected.get();
        ContentSniffer.Flaw flaw;
        try (InputStream content = file.getInputStream()) {
            flaw = ContentSniffer.flawOf(type, content);
        } catch (IOException ex) {
            throw new UploadRefusedException("file.unreadable");
        }
        if (flaw == ContentSniffer.Flaw.MACRO) {
            throw new UploadRefusedException("file.upload.macro");
        }
        if (flaw != null) {
            throw new UploadRefusedException("file.contentMismatch", extension);
        }
        return new VettedUpload(type, extension, file.getSize());
    }

    /**
     * On a path that takes photos: an accepted raster image by its bytes,
     * whatever it is named, kept under the extension of the image it is (its
     * own when the name already says so). Empty when the bytes are no such
     * image and the path also takes other files, which the name rule then
     * decides.
     */
    private static Optional<VettedUpload> photoByBytes(MultipartFile file, UploadKind kind, String named) {
        Optional<UploadType> image = ContentSniffer.detect(headOf(file), named)
            .filter(UploadType::isRasterImage)
            .filter(kind.allowedTypes()::contains);
        if (image.isEmpty()) {
            if (!kind.isRasterOnly()) {
                return Optional.empty();
            }
            throw kind.acceptsExtension(named)
                ? new UploadRefusedException("file.contentMismatch", named)
                : notAccepted(kind);
        }
        UploadType type = image.get();
        String extension = type.extensions().contains(named) ? named : type.extension();
        return Optional.of(new VettedUpload(type, extension, file.getSize()));
    }

    /** The refusal of a file that is not of a type the path takes. */
    private static UploadRefusedException notAccepted(UploadKind kind) {
        return new UploadRefusedException(kind.unsupportedKey());
    }

    private static byte[] headOf(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(ContentSniffer.HEAD_BYTES);
        } catch (IOException ex) {
            throw new UploadRefusedException("file.unreadable");
        }
    }

    /** The extension of a file name, lower case with its dot; empty when it has none. */
    public static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    /** What this request already checked for this very file; {@code null} outside a request. */
    @SuppressWarnings("unchecked")
    private static Map<UploadKind, VettedUpload> knownFor(MultipartFile file) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object held = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        Map<MultipartFile, Map<UploadKind, VettedUpload>> byFile;
        if (held instanceof IdentityHashMap<?, ?> existing) {
            byFile = (Map<MultipartFile, Map<UploadKind, VettedUpload>>) existing;
        } else {
            byFile = new IdentityHashMap<>();
            attributes.setAttribute(ATTRIBUTE, byFile, RequestAttributes.SCOPE_REQUEST);
        }
        return byFile.computeIfAbsent(file, ignored -> new EnumMap<>(UploadKind.class));
    }
}
