package com.example.hms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.hms.exception.ResourceNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

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
        service = new FileUploadService();
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        Files.createDirectories(tempDir.resolve("patient-documents"));
        Files.writeString(tempDir.resolve("patient-documents").resolve("doc.pdf"), "pdf-bytes");
    }

    @Test
    void resolvesARelativeStorageKey() {
        Path path = service.resolveStoredFile("/uploads/patient-documents/doc.pdf", "patient-documents");

        assertThat(path).exists();
        assertThat(path.getFileName().toString()).isEqualTo("doc.pdf");
    }

    @Test
    void resolvesALegacyAbsoluteUrl() {
        Path path = service.resolveStoredFile(
            "https://hms.example.com/api/uploads/patient-documents/doc.pdf", "patient-documents");

        assertThat(path).exists();
    }

    @Test
    void aKeyFromAnotherSubdirectoryIsRefused() {
        // A chat storage key must never resolve through the document gate.
        assertThatThrownBy(() -> service.resolveStoredFile(
                "/uploads/chat-attachments/doc.pdf", "patient-documents"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void traversalHasNothingToGrab() {
        assertThatThrownBy(() -> service.resolveStoredFile(
                "/uploads/patient-documents/../secrets.txt", "patient-documents"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aVanishedFileIsNotFound() {
        assertThatThrownBy(() -> service.resolveStoredFile(
                "/uploads/patient-documents/gone.pdf", "patient-documents"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void nullKeysAreNotFound() {
        assertThatThrownBy(() -> service.resolveStoredFile(null, "patient-documents"))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
