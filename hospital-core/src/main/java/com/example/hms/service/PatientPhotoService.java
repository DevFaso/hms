package com.example.hms.service;

import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Patient photo storage (P3 #21).
 *
 * <p>Photos are PHI: the binary is only reachable through the
 * authenticated streaming endpoint. They are kept in the file store under
 * {@code patient-photos/}; the one stored class served without a token is
 * profile images ({@code ProfileImageController}), so nothing else in the
 * store has a public path.
 */
public interface PatientPhotoService {

    LocalDateTime upload(UUID patientId, UUID hospitalId, MultipartFile file);

    /** The stored binary + content type, for authenticated streaming. */
    PhotoPayload load(UUID patientId, UUID hospitalId);

    void delete(UUID patientId, UUID hospitalId);

    /**
     * Plain class, not a record: a record with an array component gets a
     * generated equals/hashCode that ignores array CONTENT (Sonar
     * java:S6218), and this payload is never compared — only streamed.
     */
    final class PhotoPayload {
        private final byte[] bytes;
        private final String contentType;

        public PhotoPayload(byte[] bytes, String contentType) {
            this.bytes = bytes;
            this.contentType = contentType;
        }

        public byte[] bytes() {
            return bytes;
        }

        public String contentType() {
            return contentType;
        }
    }
}
