package com.example.hms.upload;

import java.io.IOException;
import java.io.InputStream;

/**
 * The malware check of a stored upload. The application ships
 * {@link NoOpUploadScanner}; declaring any other bean of this type replaces it
 * (docs/runbooks/upload-malware-scanning.md).
 *
 * <p>The file is already in the storage port when it is scanned. A verdict
 * other than {@link Verdict#CLEAN} keeps it there, unserved:
 * {@link Verdict#UNKNOWN} (the scanner could not answer) is checked again by
 * {@code UploadRescanScheduler}; {@link Verdict#INFECTED} is final.
 */
public interface UploadScanner {

    /** A short name kept on the record with each verdict, such as {@code clamav}. */
    String engine();

    /**
     * Check one stored file. An exception counts as {@link Verdict#UNKNOWN}.
     */
    Verdict scan(Upload upload) throws IOException;

    enum Verdict { CLEAN, INFECTED, UNKNOWN }

    /** Opens the stored bytes; the scanner closes the stream. */
    @FunctionalInterface
    interface Content {
        InputStream open() throws IOException;
    }

    /** The file to check: its key in the storage port, the type its content showed, its size. */
    record Upload(String storageKey, String contentType, long sizeBytes, Content content) {
    }
}
