package com.example.hms.service;

import com.example.hms.enums.ChatAttachmentKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileUploadServiceChatAttachmentTest {

    private static final UUID UPLOADER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private FileUploadService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new FileUploadService();
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://hms.example");
        ReflectionTestUtils.setField(service, "backendBaseUrl", "http://localhost:8081");
    }

    @Test
    void uploadChatAttachment_storesPhotoAndReturnsDescriptor() throws IOException {
        byte[] payload = "fake-jpeg-bytes".getBytes();
        MockMultipartFile file = new MockMultipartFile(
            "file", "patient-rash.jpg", "image/jpeg", payload);

        FileUploadService.StoredFileDescriptor descriptor =
            service.uploadChatAttachment(file, ChatAttachmentKind.PHOTO, UPLOADER);

        assertNotNull(descriptor.storageKey());
        assertTrue(descriptor.storageKey().startsWith("/uploads/chat-attachments/"));
        assertEquals(payload.length, descriptor.sizeBytes());
        assertEquals("image/jpeg", descriptor.contentType());
        assertNotNull(descriptor.sha256());
        assertEquals(64, descriptor.sha256().length());

        Path stored = tempDir.resolve("chat-attachments")
            .resolve(descriptor.storageKey().substring("/uploads/chat-attachments/".length()));
        assertTrue(Files.exists(stored));
    }

    @Test
    void uploadChatAttachment_storesAudio() throws IOException {
        byte[] payload = new byte[1024];
        MockMultipartFile file = new MockMultipartFile(
            "file", "memo.ogg", "audio/ogg", payload);

        FileUploadService.StoredFileDescriptor descriptor =
            service.uploadChatAttachment(file, ChatAttachmentKind.AUDIO, UPLOADER);

        assertTrue(descriptor.storageKey().contains("/chat-attachments/"));
        assertTrue(descriptor.storageKey().endsWith(".ogg"));
        assertEquals("audio/ogg", descriptor.contentType());
    }

    @Test
    void uploadChatAttachment_rejectsNullKind() {
        MockMultipartFile file = new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[]{1});
        assertThrows(IllegalArgumentException.class,
            () -> service.uploadChatAttachment(file, null, UPLOADER));
    }

    @Test
    void uploadChatAttachment_rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[0]);
        assertThrows(IllegalArgumentException.class,
            () -> service.uploadChatAttachment(file, ChatAttachmentKind.PHOTO, UPLOADER));
    }

    @Test
    void uploadChatAttachment_rejectsPhotoOverTenMegabytes() {
        byte[] huge = new byte[(10 * 1024 * 1024) + 1];
        MockMultipartFile file = new MockMultipartFile("file", "x.jpg", "image/jpeg", huge);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.uploadChatAttachment(file, ChatAttachmentKind.PHOTO, UPLOADER));
        assertTrue(ex.getMessage().contains("10MB"));
    }

    @Test
    void uploadChatAttachment_rejectsAudioOverFiveMegabytes() {
        byte[] huge = new byte[(5 * 1024 * 1024) + 1];
        MockMultipartFile file = new MockMultipartFile("file", "x.ogg", "audio/ogg", huge);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.uploadChatAttachment(file, ChatAttachmentKind.AUDIO, UPLOADER));
        assertTrue(ex.getMessage().contains("5MB"));
    }

    @Test
    void uploadChatAttachment_rejectsUnsupportedPhotoExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "x.gif", "image/gif", new byte[]{1});
        assertThrows(IllegalArgumentException.class,
            () -> service.uploadChatAttachment(file, ChatAttachmentKind.PHOTO, UPLOADER));
    }

    @Test
    void uploadChatAttachment_rejectsUnsupportedPhotoMime() {
        MockMultipartFile file = new MockMultipartFile("file", "x.jpg", "application/pdf", new byte[]{1});
        assertThrows(IllegalArgumentException.class,
            () -> service.uploadChatAttachment(file, ChatAttachmentKind.PHOTO, UPLOADER));
    }

    @Test
    void uploadChatAttachment_rejectsUnsupportedAudioExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "x.flac", "audio/flac", new byte[]{1});
        assertThrows(IllegalArgumentException.class,
            () -> service.uploadChatAttachment(file, ChatAttachmentKind.AUDIO, UPLOADER));
    }
}
