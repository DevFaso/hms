package com.example.hms.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Picks the {@link FileStorage} adapter from {@code app.storage.type}
 * (env {@code APP_STORAGE_TYPE}):
 * <ul>
 *   <li>{@code local} (default): files under {@code app.upload.dir}, as before;</li>
 *   <li>{@code s3}: an S3-compatible bucket (Cloudflare R2), configured by
 *       {@code app.storage.s3.endpoint / bucket / access-key-id / secret-access-key}
 *       (env {@code S3_ENDPOINT}, {@code S3_BUCKET}, {@code S3_ACCESS_KEY_ID},
 *       {@code S3_SECRET_ACCESS_KEY}). A missing value stops the start-up with a
 *       message naming the property, never a value. While {@code app.upload.dir}
 *       still exists (the old volume), reads fall back to it.</li>
 * </ul>
 * Runbook: docs/runbooks/object-storage.md.
 */
@Configuration
public class FileStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(FileStorageConfig.class);

    static final String TYPE_LOCAL = "local";
    static final String TYPE_S3 = "s3";

    @Bean
    public FileStorage fileStorage(
        @Value("${app.storage.type:local}") String type,
        @Value("${app.upload.dir:uploads}") String uploadDir,
        @Value("${app.storage.s3.endpoint:}") String endpoint,
        @Value("${app.storage.s3.bucket:}") String bucket,
        @Value("${app.storage.s3.access-key-id:}") String accessKeyId,
        @Value("${app.storage.s3.secret-access-key:}") String secretAccessKey,
        @Value("${app.storage.s3.region:auto}") String region
    ) {
        FileStorage storage = create(type, uploadDir,
            new S3Settings(endpoint, bucket, accessKeyId, secretAccessKey, region));
        if (log.isInfoEnabled()) {
            log.info("File storage: {}", storage.describe());
        }
        return storage;
    }

    /** The adapter for {@code type}; package-visible for the start-up checks' tests. */
    static FileStorage create(String type, String uploadDir, S3Settings s3) {
        String normalized = type == null ? "" : type.strip().toLowerCase(Locale.ROOT);
        LocalFileStorage local = new LocalFileStorage(Paths.get(uploadDir));
        if (normalized.isEmpty() || TYPE_LOCAL.equals(normalized)) {
            return local;
        }
        if (!TYPE_S3.equals(normalized)) {
            throw new StorageMisconfiguredException("app.storage.type (APP_STORAGE_TYPE) must be 'local' or 's3'");
        }
        s3.requireComplete();
        LocalFileStorage fallback = Files.isDirectory(local.root()) ? local : null;
        return new S3FileStorage(s3Client(s3), s3.bucket().strip(), fallback);
    }

    static S3Client s3Client(S3Settings s3) {
        return S3Client.builder()
            .endpointOverride(URI.create(s3.endpoint().strip()))
            .region(Region.of(s3.region() == null || s3.region().isBlank() ? "auto" : s3.region().strip()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(s3.accessKeyId().strip(), s3.secretAccessKey().strip())))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            // R2 (and most S3-compatible stores) reject the SDK's default
            // flexible-checksum trailers; send and verify checksums only where
            // the API requires them.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    }

    /**
     * A start-up refusal: the storage settings are incomplete or wrong. It stops
     * the application context, so it never answers a request; its text is for
     * the operator reading the deploy log and names settings, never values.
     */
    static final class StorageMisconfiguredException extends IllegalStateException {
        StorageMisconfiguredException(String message) {
            super(message);
        }
    }

    /** The bucket settings. {@link #toString()} never prints the credentials. */
    record S3Settings(String endpoint, String bucket, String accessKeyId, String secretAccessKey, String region) {

        void requireComplete() {
            List<String> missing = new ArrayList<>();
            if (isBlank(endpoint)) {
                missing.add("app.storage.s3.endpoint (S3_ENDPOINT)");
            }
            if (isBlank(bucket)) {
                missing.add("app.storage.s3.bucket (S3_BUCKET)");
            }
            if (isBlank(accessKeyId)) {
                missing.add("app.storage.s3.access-key-id (S3_ACCESS_KEY_ID)");
            }
            if (isBlank(secretAccessKey)) {
                missing.add("app.storage.s3.secret-access-key (S3_SECRET_ACCESS_KEY)");
            }
            if (!missing.isEmpty()) {
                throw new StorageMisconfiguredException("app.storage.type=s3 but these settings are missing: "
                    + String.join(", ", missing));
            }
            URI uri;
            try {
                uri = URI.create(endpoint.strip());
            } catch (IllegalArgumentException ex) {
                throw new StorageMisconfiguredException("app.storage.s3.endpoint (S3_ENDPOINT) is not a valid URL");
            }
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new StorageMisconfiguredException("app.storage.s3.endpoint (S3_ENDPOINT) must be an http(s) URL");
            }
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        @Override
        public String toString() {
            return "S3Settings[endpoint=" + endpoint + ", bucket=" + bucket + ", region=" + region
                + ", accessKeyId=***, secretAccessKey=***]";
        }
    }
}
