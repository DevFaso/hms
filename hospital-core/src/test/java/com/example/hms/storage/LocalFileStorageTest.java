package com.example.hms.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalFileStorageTest extends FileStorageContractTest {

    @TempDir
    Path tempDir;

    private LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFileStorage(tempDir.resolve("uploads"));
    }

    @Override
    protected FileStorage storage() {
        return storage;
    }

    @Test
    void aKeyIsThePathTheFileAlwaysHadUnderTheUploadRoot() throws Exception {
        byte[] content = "pdf".getBytes(StandardCharsets.UTF_8);
        storage.put("patient-documents/d.pdf", new ByteArrayInputStream(content), content.length, "application/pdf");

        // Same layout as before the port: rows stored then still resolve.
        assertThat(tempDir.resolve("uploads").resolve("patient-documents").resolve("d.pdf")).hasContent("pdf");
    }

    @Test
    void aDirectoryIsNotAStoredObject() throws Exception {
        Files.createDirectories(tempDir.resolve("uploads").resolve("profile-images").resolve("sub"));

        assertThat(storage.get("profile-images/sub")).isEmpty();
        assertThat(storage.exists("profile-images/sub")).isFalse();
    }
}
