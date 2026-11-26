package com.example.hms.service;

import com.example.hms.enums.ChatAttachmentKind;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.storage.LocalFileStorage;
import com.example.hms.testsupport.UploadSamples;
import com.example.hms.upload.UploadKind;
import com.example.hms.upload.UploadLedger;
import com.example.hms.upload.UploadScanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every path of {@link FileUploadService} records the file it stored, with the
 * type its content showed, and none opens a file whose malware check is not
 * clean.
 */
@DisplayName("Stored uploads are recorded, and a quarantined file is not opened")
class FileUploadServiceUploadLedgerTest {

    private static final UUID UPLOADER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path tempDir;

    private final UploadLedger ledger = mock(UploadLedger.class);
    private FileUploadService service;

    @BeforeEach
    void setUp() {
        service = new FileUploadService(new LocalFileStorage(tempDir), ledger);
        ReflectionTestUtils.setField(service, "patientDocumentUrlPrefix", "/uploads");
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://hms.example");
        ReflectionTestUtils.setField(service, "backendBaseUrl", "http://localhost:8081");
        when(ledger.record(any())).thenReturn(UploadScanStatus.CLEAN);
    }

    private static MockMultipartFile file(String name, String declared, byte[] content) {
        return new MockMultipartFile("file", name, declared, content);
    }

    @Test
    @DisplayName("each upload path records its file under its kind, with the derived type and the sender")
    void everyPathRecordsItsUpload() throws Exception {
        service.uploadProfileImage(file("me.png", "image/svg+xml", UploadSamples.png()), UPLOADER);
        service.uploadReferralAttachment(file("ref.pdf", "text/html", UploadSamples.pdf()), UPLOADER);
        service.uploadChartAttachment(file("chart.jpg", "application/octet-stream", UploadSamples.jpeg()), UPLOADER);
        service.uploadPatientDocument(file("doc.docx", "application/zip", UploadSamples.docx(false)), UPLOADER);
        service.uploadChatAttachment(file("p.webp", "image/png", UploadSamples.webp()), ChatAttachmentKind.PHOTO,
            UPLOADER);
        service.uploadChatAttachment(file("v.ogg", "video/ogg", UploadSamples.ogg()), ChatAttachmentKind.AUDIO,
            UPLOADER);
        service.uploadImagingImage(file("thorax.jpeg", "image/gif", UploadSamples.jpeg()));

        ArgumentCaptor<UploadLedger.StoredUpload> recorded = ArgumentCaptor.forClass(UploadLedger.StoredUpload.class);
        verify(ledger, times(7)).record(recorded.capture());
        List<UploadLedger.StoredUpload> uploads = recorded.getAllValues();
        assertThat(uploads).extracting(UploadLedger.StoredUpload::kind).containsExactly(
            UploadKind.PROFILE_IMAGE, UploadKind.REFERRAL_ATTACHMENT, UploadKind.CHART_ATTACHMENT,
            UploadKind.PATIENT_DOCUMENT, UploadKind.CHAT_PHOTO, UploadKind.CHAT_AUDIO, UploadKind.IMAGING_IMAGE);
        assertThat(uploads).extracting(UploadLedger.StoredUpload::contentType).containsExactly(
            "image/png", "application/pdf", "image/jpeg",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "image/webp", "audio/ogg", "image/jpeg");
        assertThat(uploads.subList(0, 6)).extracting(UploadLedger.StoredUpload::uploaderId).containsOnly(UPLOADER);
        for (UploadLedger.StoredUpload upload : uploads) {
            assertThat(tempDir.resolve(upload.storageKey())).as(upload.storageKey()).exists();
            assertThat(upload.sizeBytes()).isPositive();
            assertThat(upload.sha256()).hasSize(64);
        }
    }

    @Test
    @DisplayName("a file the malware check refuses is not linked: the upload is refused and the file kept")
    void anInfectedUploadIsRefusedAndKept() throws Exception {
        when(ledger.record(any())).thenReturn(UploadScanStatus.INFECTED);

        assertThatThrownBy(() -> service.uploadPatientDocument(
                file("doc.pdf", "application/pdf", UploadSamples.pdf()), UPLOADER))
            .isInstanceOf(IllegalArgumentException.class);

        try (Stream<Path> kept = Files.list(tempDir.resolve("patient-documents"))) {
            assertThat(kept).hasSize(1);
        }
    }

    @Test
    @DisplayName("a file whose check is pending is stored, and is not opened until it is clean")
    void aPendingUploadIsNotOpened() throws Exception {
        when(ledger.record(any())).thenReturn(UploadScanStatus.PENDING);
        FileUploadService.StoredFileDescriptor stored = service.uploadPatientDocument(
            file("doc.pdf", "application/pdf", UploadSamples.pdf()), UPLOADER);
        String key = stored.storageKey().substring("/uploads/".length());
        doThrow(new ResourceNotFoundException("file.upload.pendingCheck")).when(ledger).requireServable(key);

        assertThatThrownBy(() -> service.openStoredFile(stored.storageKey(), "patient-documents"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasFieldOrPropertyWithValue("messageKey", "file.upload.pendingCheck");
    }

    @Test
    @DisplayName("a quarantined chat attachment cannot be linked to a message")
    void aQuarantinedChatAttachmentIsNotLinked() throws Exception {
        FileUploadService.StoredFileDescriptor stored = service.uploadChatAttachment(
            file("p.jpg", "image/jpeg", UploadSamples.jpeg()), ChatAttachmentKind.PHOTO, UPLOADER);
        doThrow(new ResourceNotFoundException("file.upload.blocked")).when(ledger).requireServable(anyString());

        assertThatThrownBy(() -> service.resolveChatAttachment(stored.storageKey(), ChatAttachmentKind.PHOTO))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a clean file is opened after the ledger was asked")
    void aCleanFileIsOpened() throws Exception {
        FileUploadService.StoredFileDescriptor stored = service.uploadPatientDocument(
            file("doc.pdf", "application/pdf", UploadSamples.pdf()), UPLOADER);

        try (var object = service.openStoredFile(stored.storageKey(), "patient-documents")) {
            assertThat(object.content().readAllBytes()).isEqualTo(UploadSamples.pdf());
        }
        verify(ledger).requireServable(stored.storageKey().substring("/uploads/".length()));
    }
}
