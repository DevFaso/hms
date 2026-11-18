package com.example.hms.storage;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * An object read from {@link FileStorage}: its bytes, opened, and its length.
 * Whoever holds it closes it (a bucket read holds an HTTP connection open).
 */
public record StoredObject(InputStream content, long length) implements Closeable {

    @Override
    public void close() throws IOException {
        content.close();
    }
}
