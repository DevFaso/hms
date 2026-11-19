package com.example.hms.storage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The S3 adapter against a real S3-compatible server (Adobe S3Mock; MinIO no
 * longer publishes public images), built exactly
 * as production builds it ({@link FileStorageConfig#s3Client}: endpoint
 * override, path-style, checksums only when required). Each test gets its
 * own bucket. Runs wherever Docker is available (CI); skipped otherwise.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3FileStorageIT extends FileStorageContractTest {

    private static final int S3_PORT = 9090;

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> S3 = new GenericContainer<>("adobe/s3mock:4.9.1")
        .withExposedPorts(S3_PORT)
        .waitingFor(Wait.forListeningPort());

    private static S3Client client;

    @TempDir
    Path tempDir;

    private S3FileStorage storage;
    private String bucket;

    @BeforeAll
    static void connect() {
        String endpoint = "http://" + S3.getHost() + ":" + S3.getMappedPort(S3_PORT);
        client = FileStorageConfig.s3Client(new FileStorageConfig.S3Settings(
            endpoint, "unused", "test-key-id", "test-secret", "auto"));
    }

    @AfterAll
    static void disconnect() {
        if (client != null) {
            client.close();
        }
    }

    @BeforeEach
    void newBucket() {
        bucket = "hms-" + UUID.randomUUID();
        client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        storage = new S3FileStorage(client, bucket, null);
    }

    @Override
    protected FileStorage storage() {
        return storage;
    }

    @Test
    void duringTheCutOverAMissIsReadFromTheOldVolumeAndDeleteClearsBoth() throws Exception {
        Path volume = tempDir.resolve("uploads");
        Files.createDirectories(volume.resolve("patient-photos"));
        Files.writeString(volume.resolve("patient-photos").resolve("p.png"), "old");
        S3FileStorage cutOver = new S3FileStorage(client, bucket, new LocalFileStorage(volume));

        try (StoredObject stored = cutOver.get("patient-photos/p.png").orElseThrow()) {
            assertThat(new String(stored.content().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("old");
        }
        // exists() answers for the bucket only: the backfill relies on it.
        assertThat(cutOver.exists("patient-photos/p.png")).isFalse();

        cutOver.delete("patient-photos/p.png");
        assertThat(volume.resolve("patient-photos").resolve("p.png")).doesNotExist();
        assertThat(cutOver.get("patient-photos/p.png")).isEmpty();
    }

    @Test
    void theBackfillCopiesWhatTheBucketLacksAndIsIdempotent() throws Exception {
        Path volume = tempDir.resolve("uploads");
        Files.createDirectories(volume.resolve("profile-images"));
        Files.createDirectories(volume.resolve("patient-documents"));
        Files.writeString(volume.resolve("profile-images").resolve("a.png"), "a");
        Files.writeString(volume.resolve("patient-documents").resolve("b.pdf"), "b");
        byte[] already = "already".getBytes(StandardCharsets.UTF_8);
        storage.put("patient-documents/b.pdf", new ByteArrayInputStream(already), already.length, "application/pdf");

        StorageBackfill.Result first = StorageBackfill.run(new LocalFileStorage(volume), storage);
        StorageBackfill.Result second = StorageBackfill.run(new LocalFileStorage(volume), storage);

        assertThat(first).isEqualTo(new StorageBackfill.Result(1, 1, 0));
        assertThat(second).isEqualTo(new StorageBackfill.Result(0, 2, 0));
        // The bucket's own copy is never overwritten, and the volume is never touched.
        try (StoredObject stored = storage.get("patient-documents/b.pdf").orElseThrow()) {
            assertThat(new String(stored.content().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("already");
        }
        assertThat(volume.resolve("profile-images").resolve("a.png")).hasContent("a");
    }
}
