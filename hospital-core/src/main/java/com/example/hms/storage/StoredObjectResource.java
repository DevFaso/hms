package com.example.hms.storage;

import org.springframework.core.io.InputStreamResource;

/**
 * A {@link StoredObject} as a response body: streamed once, with its length
 * as the {@code Content-Length} (a plain {@link InputStreamResource} has
 * none). Spring closes the stream after writing it.
 */
// Equality stays the parent's (the same open stream is the same resource); the
// length is derived from that stream's object, so it adds nothing to compare.
@SuppressWarnings("java:S2160")
public class StoredObjectResource extends InputStreamResource {

    private final long length;

    public StoredObjectResource(StoredObject object) {
        super(object.content(), "stored object");
        this.length = object.length();
    }

    @Override
    public long contentLength() {
        return length;
    }
}
