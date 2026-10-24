package com.example.hms.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QA D540: a patient upload of HTML declared as a PDF was stored and served as a PDF. */
class FileUploadServiceContentSniffTest {

    @TempDir
    Path tempDir;

    private FileUploadService service;

    @BeforeEach
    void setUp() {
        service = new FileUploadService();
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "patientDocumentUrlPrefix", "/uploads");
        ReflectionTestUtils.setField(service, "backendBaseUrl", "http://localhost:8080");
    }

    @Test
    void htmlDeclaredAsAPdfIsRefused() {
        MockMultipartFile html = new MockMultipartFile("file", "report.pdf", "application/pdf",
            "<!DOCTYPE html><html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> service.uploadPatientDocument(html, userId))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(tempDir.resolve("patient-documents")).satisfiesAnyOf(
            dir -> assertThat(Files.exists(dir)).isFalse(),
            dir -> assertThat(dir).isEmptyDirectory());
    }

    @Test
    void aRealPdfIsStoredWithThePdfTypeWhateverTheClientDeclared() throws Exception {
        MockMultipartFile pdf = new MockMultipartFile("file", "report.pdf", "text/html",
            "%PDF-1.7\n1 0 obj\n".getBytes(StandardCharsets.US_ASCII));
        FileUploadService.StoredFileDescriptor stored = service.uploadPatientDocument(pdf, UUID.randomUUID());
        assertThat(stored.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void chartAndReferralAttachmentsAreSniffedToo() {
        MockMultipartFile fakePng = new MockMultipartFile("file", "xray.png", "image/png",
            "<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8));
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> service.uploadChartAttachment(fakePng, userId))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.uploadReferralAttachment(fakePng, userId))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachAllowedTypeIsRecognisedByItsSignature() {
        assertThat(FileUploadService.contentMatches(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, ".png")).isTrue();
        assertThat(FileUploadService.contentMatches(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}, ".jpg")).isTrue();
        assertThat(FileUploadService.contentMatches("GIF89a".getBytes(StandardCharsets.US_ASCII), ".gif")).isTrue();
        assertThat(FileUploadService.contentMatches(new byte[] {'I', 'I', 0x2A, 0x00}, ".tiff")).isTrue();
        assertThat(FileUploadService.contentMatches(new byte[] {'P', 'K', 0x03, 0x04}, ".docx")).isTrue();
        assertThat(FileUploadService.contentMatches("{\\rtf1".getBytes(StandardCharsets.US_ASCII), ".rtf")).isTrue();
        assertThat(FileUploadService.contentMatches("Blood pressure 120/80".getBytes(StandardCharsets.UTF_8), ".txt")).isTrue();
        assertThat(FileUploadService.contentMatches("<html><body>x".getBytes(StandardCharsets.UTF_8), ".txt")).isFalse();
    }

    @Test
    void qaD643_anUnsupportedDocumentTypeIsRefusedInTheReaderLanguage() {
        MockMultipartFile exe = new MockMultipartFile("file", "setup.exe", "application/octet-stream",
            new byte[] {'M', 'Z', 0x00});
        UUID userId = UUID.randomUUID();
        org.springframework.context.MessageSource previous = (org.springframework.context.MessageSource)
            ReflectionTestUtils.getField(com.example.hms.utility.MessageUtil.class, "messageSource");
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.FRENCH);
        try {
            assertThatThrownBy(() -> service.uploadPatientDocument(exe, userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Type de document non pris en charge");
            MockMultipartFile empty = new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[0]);
            assertThatThrownBy(() -> service.uploadPatientDocument(empty, userId))
                .hasMessage("Le fichier est vide.");
            org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.forLanguageTag("es"));
            assertThatThrownBy(() -> service.uploadPatientDocument(exe, userId))
                .hasMessageStartingWith("Tipo de documento no admitido");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
            com.example.hms.utility.MessageUtil.setMessageSource(previous);
        }
    }
}
