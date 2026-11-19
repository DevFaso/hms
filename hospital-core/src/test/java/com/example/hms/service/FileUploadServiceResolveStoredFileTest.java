package com.example.hms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.storage.StoredObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The single resolution gate every authenticated download endpoint goes
 * through (the /uploads permitAll fix): storage keys resolve only inside
 * their own subdirectory, and both the relative and the legacy absolute
 * URL forms work — historical rows store full origins from the
 * permitAll era.
 */
class FileUploadServiceResolveStoredFileTest {

    @TempDir
    Path tempDir;

    private FileUploadService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new FileUploadService(new com.example.hms.storage.LocalFileStorage(tempDir));
        Files.createDirectories(tempDir.resolve("patient-documents"));
        Files.writeString(tempDir.resolve("patient-documents").resolve("doc.pdf"), "pdf-bytes");
    }

    @Test
    void resolvesARelativeStorageKey() throws Exception {
        try (StoredObject stored = service.openStoredFile("/uploads/patient-documents/doc.pdf", "patient-documents")) {
            assertThat(new String(stored.content().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("pdf-bytes");
            assertThat(stored.length()).isEqualTo(9);
        }
    }

    @Test
    void resolvesALegacyAbsoluteUrl() throws Exception {
        try (StoredObject stored = service.openStoredFile(
            "https://hms.example.com/api/uploads/patient-documents/doc.pdf", "patient-documents")) {
            assertThat(stored.length()).isEqualTo(9);
        }
    }

    @Test
    void deleteRemovesTheStoredFileAndIsQuietWhenItIsGone() throws Exception {
        service.deleteStoredFile("/uploads/patient-documents/doc.pdf", "patient-documents");
        assertThat(tempDir.resolve("patient-documents").resolve("doc.pdf")).doesNotExist();
        service.deleteStoredFile("/uploads/patient-documents/doc.pdf", "patient-documents");
    }

    @Test
    void aKeyFromAnotherSubdirectoryIsRefused() {
        // A chat storage key must never resolve through the document gate.
        assertThatThrownBy(() -> service.openStoredFile(
                "/uploads/chat-attachments/doc.pdf", "patient-documents"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void traversalHasNothingToGrab() {
        assertThatThrownBy(() -> service.openStoredFile(
                "/uploads/patient-documents/../secrets.txt", "patient-documents"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aVanishedFileIsNotFound() {
        assertThatThrownBy(() -> service.openStoredFile(
                "/uploads/patient-documents/gone.pdf", "patient-documents"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void nullKeysAreNotFound() {
        assertThatThrownBy(() -> service.openStoredFile(null, "patient-documents"))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
