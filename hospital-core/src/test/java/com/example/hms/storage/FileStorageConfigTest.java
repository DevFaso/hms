package com.example.hms.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code app.storage.type} picks the adapter, and an incomplete s3 setup stops the start-up. */
class FileStorageConfigTest {

    private static final String SECRET = "very-secret-value";

    @TempDir
    Path tempDir;

    private static FileStorageConfig.S3Settings complete() {
        return new FileStorageConfig.S3Settings(
            "https://account.r2.cloudflarestorage.com", "hms-files", "key-id", SECRET, "auto");
    }

    @Test
    void localIsTheDefault() {
        assertThat(FileStorageConfig.create("local", tempDir.toString(), complete()))
            .isInstanceOf(LocalFileStorage.class);
        assertThat(FileStorageConfig.create("", tempDir.toString(), complete()))
            .isInstanceOf(LocalFileStorage.class);
        assertThat(FileStorageConfig.create(null, tempDir.toString(), complete()))
            .isInstanceOf(LocalFileStorage.class);
    }

    @Test
    void anUnknownTypeIsRefused() {
        assertThatThrownBy(() -> FileStorageConfig.create("gcs", tempDir.toString(), complete()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APP_STORAGE_TYPE");
    }

    @Test
    void s3WithAMissingValueNamesEveryMissingSettingAndNoValue() {
        FileStorageConfig.S3Settings partial = new FileStorageConfig.S3Settings(
            "https://account.r2.cloudflarestorage.com", " ", null, SECRET, "auto");

        assertThatThrownBy(() -> FileStorageConfig.create("s3", tempDir.toString(), partial))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("S3_BUCKET")
            .hasMessageContaining("S3_ACCESS_KEY_ID")
            .satisfies(ex -> assertThat(ex.getMessage())
                .doesNotContain("S3_ENDPOINT").doesNotContain("S3_SECRET_ACCESS_KEY").doesNotContain(SECRET));
    }

    @Test
    void s3WithAnEndpointThatIsNotAUrlIsRefused() {
        FileStorageConfig.S3Settings bad = new FileStorageConfig.S3Settings(
            "account.r2.cloudflarestorage.com", "b", "k", SECRET, "auto");

        assertThatThrownBy(() -> FileStorageConfig.create("S3", tempDir.toString(), bad))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("S3_ENDPOINT");
    }

    @Test
    void s3FallsBackToTheUploadDirectoryOnlyWhileItExists() throws Exception {
        Path volume = tempDir.resolve("uploads");

        FileStorage without = FileStorageConfig.create("s3", volume.toString(), complete());
        Files.createDirectories(volume);
        FileStorage with = FileStorageConfig.create("s3", volume.toString(), complete());

        assertThat(without).isInstanceOf(S3FileStorage.class);
        assertThat(((S3FileStorage) without).fallback()).isNull();
        assertThat(((S3FileStorage) with).fallback()).isNotNull();
        assertThat(with.describe()).contains("hms-files").doesNotContain(SECRET).doesNotContain("key-id");
    }

    @Test
    void theSettingsNeverPrintTheCredentials() {
        assertThat(complete().toString()).doesNotContain(SECRET).doesNotContain("key-id");
    }
}
