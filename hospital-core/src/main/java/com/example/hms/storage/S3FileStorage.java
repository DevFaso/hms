package com.example.hms.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * {@link FileStorage} on an S3-compatible bucket (Cloudflare R2 in production).
 * The bucket is private: nothing here makes a public or presigned URL, and
 * files leave the service only through its own authenticated endpoints (and
 * the one public avatar path, {@code ProfileImageController}).
 *
 * <p>Cut-over safety: when a {@code fallback} directory is given (the old
 * volume, still attached), a key the bucket does not have is read from it, and
 * a delete removes both copies. {@link StorageBackfill} copies the directory
 * into the bucket; once the counts match, the volume can be detached.
 *
 * <p>Errors are reported as {@link IOException} naming the operation only:
 * never the key (it can carry a patient's file name) nor any credential.
 */
public class S3FileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(S3FileStorage.class);
    private static final int NOT_FOUND = 404;

    private final S3Client client;
    private final String bucket;
    private final LocalFileStorage fallback;

    /**
     * @param fallback the old upload directory to read from on a miss, or {@code null}
     */
    public S3FileStorage(S3Client client, String bucket, LocalFileStorage fallback) {
        this.client = client;
        this.bucket = bucket;
        this.fallback = fallback;
    }

    @Override
    public void put(String key, InputStream content, long length, String contentType) throws IOException {
        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(StorageKeys.requireValid(key))
            .contentLength(length)
            .contentType(contentType != null ? contentType : "application/octet-stream")
            .build();
        try {
            client.putObject(request, RequestBody.fromInputStream(content, length));
        } catch (SdkException ex) {
            throw failure("put", ex);
        }
    }

    @Override
    public Optional<StoredObject> get(String key) throws IOException {
        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(bucket)
            .key(StorageKeys.requireValid(key))
            .build();
        try {
            ResponseInputStream<GetObjectResponse> stream = client.getObject(request);
            Long length = stream.response().contentLength();
            return Optional.of(new StoredObject(stream, length != null ? length : -1L));
        } catch (NoSuchKeyException ex) {
            return readFallback(key);
        } catch (S3Exception ex) {
            if (ex.statusCode() == NOT_FOUND) {
                return readFallback(key);
            }
            throw failure("get", ex);
        } catch (SdkException ex) {
            throw failure("get", ex);
        }
    }

    /** Whether the BUCKET has the key; the fallback directory is not consulted (the backfill relies on it). */
    @Override
    public boolean exists(String key) throws IOException {
        HeadObjectRequest request = HeadObjectRequest.builder()
            .bucket(bucket)
            .key(StorageKeys.requireValid(key))
            .build();
        try {
            client.headObject(request);
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        } catch (S3Exception ex) {
            if (ex.statusCode() == NOT_FOUND) {
                return false;
            }
            throw failure("head", ex);
        } catch (SdkException ex) {
            throw failure("head", ex);
        }
    }

    /** Removes the bucket copy and, during the cut-over, the copy left on the old volume. */
    @Override
    public void delete(String key) throws IOException {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
            .bucket(bucket)
            .key(StorageKeys.requireValid(key))
            .build();
        try {
            client.deleteObject(request);
        } catch (SdkException ex) {
            throw failure("delete", ex);
        }
        if (fallback != null) {
            fallback.delete(key);
        }
    }

    @Override
    public String describe() {
        return "S3-compatible bucket '" + bucket + "'"
            + (fallback != null ? " (reads fall back to " + fallback.describe() + ")" : "");
    }

    /** The cut-over source, or {@code null}; read by {@link StorageBackfill}. */
    LocalFileStorage fallback() {
        return fallback;
    }

    private Optional<StoredObject> readFallback(String key) throws IOException {
        if (fallback == null) {
            return Optional.empty();
        }
        Optional<StoredObject> local = fallback.get(key);
        if (local.isPresent()) {
            log.debug("Object storage miss served from the local fallback directory");
        }
        return local;
    }

    private static IOException failure(String operation, SdkException ex) {
        String status = ex instanceof S3Exception s3 ? " (HTTP " + s3.statusCode() + ")" : "";
        return new IOException("Object storage " + operation + " failed" + status + ": "
            + ex.getClass().getSimpleName(), ex);
    }
}
