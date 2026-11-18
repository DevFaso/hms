package com.example.hms.storage;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * An object read from {@link FileStorage}: its bytes, opened, and its length.
 * The object OWNS the stream: whoever receives it closes it (try-with-resources,
 * or by handing it to a response body that Spring closes after writing). A
 * bucket read holds an HTTP connection open until then.
 */
public record StoredObject(InputStream content, long length) implements Closeable {

    @Override
    public void close() throws IOException {
        content.close();
    }
}
