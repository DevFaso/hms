package com.example.hms.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * The one way hospital-core keeps a file a user (or the server) produced:
 * profile images, patient photos, patient documents, chart/referral/chat
 * attachments and FHIR bulk-export output.
 *
 * <p>A key is a relative path such as {@code "patient-documents/<name>"}: the
 * same path, under the upload root, that the files had on disk, so the
 * references already stored in the database keep resolving and moving to a
 * bucket needs no data migration. Every key is checked by
 * {@link StorageKeys#requireValid} inside the adapters, so no caller can reach
 * outside the store.
 *
 * <p>Two adapters: {@link LocalFileStorage} (a directory; the default, used by
 * tests and local development) and {@link S3FileStorage} (an S3-compatible
 * bucket such as Cloudflare R2). {@code app.storage.type} picks one
 * ({@link FileStorageConfig}).
 */
public interface FileStorage {

    /**
     * Store {@code length} bytes read from {@code content} under {@code key},
     * replacing any object already there. The stream is read, not closed.
     */
    void put(String key, InputStream content, long length, String contentType) throws IOException;

    /**
     * The stored object, opened for reading, or empty when there is none.
     * The caller closes the returned object.
     */
    Optional<StoredObject> get(String key) throws IOException;

    /** Whether an object is stored under {@code key}. */
    boolean exists(String key) throws IOException;

    /** Remove the object under {@code key}; nothing happens when there is none. */
    void delete(String key) throws IOException;

    /** Where the files are kept, for the startup log (never a secret). */
    String describe();
}
