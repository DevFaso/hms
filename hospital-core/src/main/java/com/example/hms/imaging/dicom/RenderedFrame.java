package com.example.hms.imaging.dicom;

import java.util.Arrays;
import java.util.Objects;

/**
 * A rendered (consumer-format) frame from WADO-RS {@code /rendered}: JPEG or PNG bytes.
 * The array is copied in and out, and compared by content.
 */
public record RenderedFrame(byte[] bytes, String contentType) {

    public RenderedFrame {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RenderedFrame that
            && Arrays.equals(bytes, that.bytes)
            && Objects.equals(contentType, that.contentType);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(bytes) + Objects.hashCode(contentType);
    }

    /** Never prints the image bytes (patient data). */
    @Override
    public String toString() {
        return "RenderedFrame{contentType=" + contentType + ", bytes=" + bytes.length + "}";
    }
}
