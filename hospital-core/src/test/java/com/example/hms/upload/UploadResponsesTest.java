package com.example.hms.upload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("A stored file leaves with its derived type, nosniff and a Content-Disposition")
class UploadResponsesTest {

    private static HttpHeaders headers(ResponseEntity.BodyBuilder response) {
        return response.build().getHeaders();
    }

    @Test
    @DisplayName("a download is an attachment, typed as stored, with nosniff")
    void aDownloadIsAnAttachment() {
        HttpHeaders headers = headers(UploadResponses.attachment("application/pdf", "bilan.pdf"));

        assertThat(headers.getContentType()).hasToString("application/pdf");
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getContentDisposition().isAttachment()).isTrue();
        assertThat(headers.getContentDisposition().getFilename()).isEqualTo("bilan.pdf");
    }

    @Test
    @DisplayName("an image is an attachment too, off a viewer path")
    void anImageIsAnAttachmentOffAViewerPath() {
        assertThat(headers(UploadResponses.attachment("image/png", "x.png")).getContentDisposition().isAttachment())
            .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png", "image/gif", "image/webp", "application/pdf"})
    @DisplayName("a viewer path shows a raster image or a PDF inline")
    void aViewerShowsImagesAndPdfInline(String type) {
        HttpHeaders headers = headers(UploadResponses.viewer(type, "x"));

        assertThat(headers.getContentType()).hasToString(type);
        assertThat(headers.getContentDisposition().isInline()).isTrue();
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/svg+xml", "text/html", "application/xhtml+xml", "text/javascript",
        "application/javascript", "text/xml", "IMAGE/SVG+XML; charset=utf-8", "application/x-unknown", ""})
    @DisplayName("a stored type the application never stores is a download of opaque bytes, on a viewer path too")
    void markupIsNeverServedInThePage(String storedType) {
        for (ResponseEntity.BodyBuilder response : new ResponseEntity.BodyBuilder[] {
            UploadResponses.viewer(storedType, "x.svg"), UploadResponses.attachment(storedType, "x.svg")}) {
            HttpHeaders headers = headers(response);
            assertThat(headers.getContentType()).hasToString("application/octet-stream");
            assertThat(headers.getContentDisposition().isAttachment()).isTrue();
            assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        }
        assertThat(headers(UploadResponses.viewer(null, null)).getContentDisposition().isAttachment()).isTrue();
    }

    @Test
    @DisplayName("word-processing, text and audio files are downloads on a viewer path")
    void onlyImagesAndPdfAreInline() {
        for (String type : new String[] {"text/plain", "text/csv", "application/msword", "application/rtf",
            "audio/ogg", "image/tiff", "image/bmp"}) {
            HttpHeaders headers = headers(UploadResponses.viewer(type, "x"));
            assertThat(headers.getContentType()).hasToString(type);
            assertThat(headers.getContentDisposition().isAttachment()).as(type).isTrue();
        }
    }

    @Test
    @DisplayName("a file name cannot break the header")
    void theFileNameIsEncoded() {
        String header = headers(UploadResponses.attachment("application/pdf", "a\"b\r\nX-Injected: 1.pdf"))
            .getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(header).doesNotContain("\r").doesNotContain("\n").startsWith("attachment;");

        String accented = headers(UploadResponses.attachment("application/pdf", "résultat été.pdf"))
            .getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(accented).contains("filename*=UTF-8''");
    }
}
