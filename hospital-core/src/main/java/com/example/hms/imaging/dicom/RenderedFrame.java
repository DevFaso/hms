package com.example.hms.imaging.dicom;

/** A rendered (consumer-format) frame from WADO-RS {@code /rendered}: JPEG or PNG bytes. */
public final class RenderedFrame {

    private final byte[] bytes;
    private final String contentType;

    public RenderedFrame(byte[] bytes, String contentType) {
        this.bytes = bytes.clone();
        this.contentType = contentType;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public String contentType() {
        return contentType;
    }
}
