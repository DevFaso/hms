package com.example.hms.service;

import com.example.hms.storage.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V316: imaging key images go through the storage port, content-checked, under a server-built name. */
class FileUploadServiceImagingImageTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0x10, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8'};
    private static final byte[] PDF = "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path tempDir;

    private FileUploadService service;

    @BeforeEach
    void setUp() {
        service = new FileUploadService(new com.example.hms.storage.LocalFileStorage(tempDir));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "thorax.jpg, image/jpeg, JPEG",
        "thorax.JPEG, image/jpeg, JPEG",
        "thorax.png, image/png, PNG",
        "thorax.webp, image/webp, WEBP",
        "export.pdf, application/pdf, PDF"
    })
    void storesEachAcceptedTypeInThePrivatePrefix(String name, String type, String bytes) throws IOException {
        byte[] content = switch (bytes) {
            case "JPEG" -> JPEG;
            case "PNG" -> PNG;
            case "WEBP" -> WEBP;
            default -> PDF;
        };
        FileUploadService.StoredFileDescriptor stored =
            service.uploadImagingImage(new MockMultipartFile("file", name, "application/octet-stream", content));

        assertThat(stored.contentType()).as("from the checked extension, not the client's header").isEqualTo(type);
        assertThat(stored.storageKey()).startsWith("/uploads/imaging-images/");
        assertThat(stored.publicUrl()).as("never a public URL").isNull();
        try (StoredObject object = service.openStoredFile(stored.storageKey(), FileUploadService.IMAGING_IMAGES_PATH)) {
            assertThat(object.content().readAllBytes()).isEqualTo(content);
        }
        assertThat(Files.list(tempDir.resolve("imaging-images")).count()).isEqualTo(1);
    }

    @Test
    void theUploadersFileNameNeverReachesTheKey() throws IOException {
        FileUploadService.StoredFileDescriptor stored = service.uploadImagingImage(
            new MockMultipartFile("file", "Jean Dupont - thorax.png", "image/png", PNG));

        assertThat(stored.storageKey()).doesNotContainIgnoringCase("dupont")
            .matches("/uploads/imaging-images/[0-9a-f-]{36}\\.png");
    }

    @Test
    void contentThatIsNotWhatTheExtensionSaysIsRefused() {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.uploadImagingImage(new MockMultipartFile("file", "x.png", "image/png", html)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.uploadImagingImage(new MockMultipartFile("file", "x.webp", "image/webp", PNG)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.uploadImagingImage(new MockMultipartFile("file", "x.pdf", "application/pdf", JPEG)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void otherTypesEmptyAndOversizedFilesAreRefused() {
        assertThatThrownBy(() -> service.uploadImagingImage(new MockMultipartFile("file", "x.gif", "image/gif",
            "GIF89a".getBytes(StandardCharsets.US_ASCII)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.uploadImagingImage(new MockMultipartFile("file", "x.dcm", "application/dicom",
            new byte[] {1}))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.uploadImagingImage(new MockMultipartFile("file", "x.png", "image/png",
            new byte[0]))).isInstanceOf(IllegalArgumentException.class);
        byte[] big = new byte[(int) FileUploadService.MAX_UPLOAD_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThatThrownBy(() -> service.uploadImagingImage(new MockMultipartFile("file", "x.png", "image/png", big)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
