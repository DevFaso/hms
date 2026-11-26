package com.example.hms.upload;

import com.example.hms.testsupport.UploadSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one check of every upload path: the type is read from the file's own
 * bytes, the client's Content-Type and extension decide nothing, and a file
 * that is markup is refused whatever it is called.
 */
@DisplayName("Uploads are typed by content, whatever the client declares")
class UploadAdmissionTest {

    private static MockMultipartFile file(String name, String declaredType, byte[] content) {
        return new MockMultipartFile("file", name, declaredType, content);
    }

    private static void refused(UploadKind kind, String name, String declaredType, byte[] content, String key) {
        assertThatThrownBy(() -> UploadAdmission.vet(file(name, declaredType, content), kind))
            .isInstanceOf(UploadRefusedException.class)
            .hasFieldOrPropertyWithValue("messageKey", key);
    }

    @Test
    @DisplayName("a PDF that is HTML is refused on every path that takes a PDF")
    void anHtmlPageNamedAsAPdfIsRefused() {
        for (UploadKind kind : new UploadKind[] {UploadKind.PATIENT_DOCUMENT, UploadKind.CHART_ATTACHMENT,
            UploadKind.REFERRAL_ATTACHMENT, UploadKind.IMAGING_IMAGE}) {
            refused(kind, "report.pdf", "application/pdf", UploadSamples.html(), "file.contentMismatch");
        }
    }

    @ParameterizedTest
    @EnumSource(UploadKind.class)
    @DisplayName("SVG, HTML and script are refused on every path, under any name and any declared type")
    void markupIsRefusedEverywhere(UploadKind kind) {
        byte[] script = "alert(document.cookie)//<script>".getBytes(StandardCharsets.UTF_8);
        for (byte[] content : new byte[][] {UploadSamples.svg(), UploadSamples.html(), script}) {
            for (String name : new String[] {"x.svg", "x.html", "x.js", "x.png", "x.jpg", "x.pdf", "x.txt", "x.csv",
                "x.ogg", "x.docx", "x"}) {
                for (String declared : new String[] {"image/svg+xml", "text/html", "image/png", "application/pdf",
                    "text/plain", null}) {
                    assertThatThrownBy(() -> UploadAdmission.vet(file(name, declared, content), kind))
                        .as("%s as %s (%s) on %s", new String(content, StandardCharsets.UTF_8), name, declared, kind)
                        .isInstanceOf(UploadRefusedException.class);
                }
            }
        }
    }

    @Test
    @DisplayName("a patient photo is a raster image by its bytes: an SVG is refused, as itself or dressed as a PNG")
    void anSvgIsNeverAPatientPhoto() {
        refused(UploadKind.PATIENT_PHOTO, "face.svg", "image/svg+xml", UploadSamples.svg(),
            "patient.photo.typeUnsupported");
        refused(UploadKind.PATIENT_PHOTO, "face.png", "image/png", UploadSamples.svg(), "file.contentMismatch");
        refused(UploadKind.PROFILE_IMAGE, "me.png", "image/png", UploadSamples.svg(), "file.contentMismatch");
        refused(UploadKind.PATIENT_PHOTO, "face.pdf", "image/png", UploadSamples.pdf(),
            "patient.photo.typeUnsupported");
    }

    @Test
    @DisplayName("the stored type is the one the content shows, never the declared one")
    void theDeclaredTypeDecidesNothing() {
        UploadAdmission.VettedUpload vetted =
            UploadAdmission.vet(file("face.JPG", "image/svg+xml", UploadSamples.jpeg()), UploadKind.PATIENT_PHOTO);
        assertThat(vetted.type()).isEqualTo(UploadType.JPEG);
        assertThat(vetted.mediaType()).isEqualTo("image/jpeg");
        assertThat(vetted.extension()).isEqualTo(".jpg");

        assertThat(UploadAdmission.vet(file("report.pdf", "text/html", UploadSamples.pdf()),
            UploadKind.PATIENT_DOCUMENT).mediaType()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("content of one accepted type under the name of another is refused")
    void theNameMustAnnounceTheContent() {
        refused(UploadKind.PATIENT_PHOTO, "face.png", "image/png", UploadSamples.jpeg(), "file.contentMismatch");
        refused(UploadKind.IMAGING_IMAGE, "x.pdf", "application/pdf", UploadSamples.png(), "file.contentMismatch");
        refused(UploadKind.CHAT_AUDIO, "memo.ogg", "audio/ogg", UploadSamples.jpeg(), "file.contentMismatch");
        refused(UploadKind.PATIENT_DOCUMENT, "notes.pdf", "application/pdf",
            "plain text".getBytes(StandardCharsets.UTF_8), "file.contentMismatch");
    }

    @Test
    @DisplayName("a file over the size of its path is refused before anything is read")
    void anOversizeFileIsRefused() {
        byte[] photo = UploadSamples.jpeg(5 * 1024 * 1024 + 1);
        refused(UploadKind.PATIENT_PHOTO, "face.jpg", "image/jpeg", photo, "patient.photo.tooLarge");
        refused(UploadKind.PROFILE_IMAGE, "me.jpg", "image/jpeg", photo, "file.image.tooLarge");
        refused(UploadKind.CHAT_AUDIO, "memo.ogg", "audio/ogg", UploadSamples.ogg(5 * 1024 * 1024 + 1),
            "file.voice.tooLarge");
        byte[] document = Arrays.copyOf(UploadSamples.pdf(), 10 * 1024 * 1024 + 1);
        refused(UploadKind.PATIENT_DOCUMENT, "report.pdf", "application/pdf", document, "file.document.tooLarge");
        refused(UploadKind.IMAGING_IMAGE, "x.pdf", "application/pdf", document, "imaging.image.tooLarge");
    }

    @Test
    @DisplayName("an empty file is refused")
    void anEmptyFileIsRefused() {
        refused(UploadKind.PATIENT_DOCUMENT, "report.pdf", "application/pdf", new byte[0], "file.empty");
    }

    @Test
    @DisplayName("a word-processing document must be a real one, and one that carries macros is refused")
    void aDocumentWithMacrosIsRefused() {
        assertThat(UploadAdmission.vet(file("letter.docx", "application/octet-stream", UploadSamples.docx(false)),
            UploadKind.PATIENT_DOCUMENT).type()).isEqualTo(UploadType.DOCX);
        refused(UploadKind.PATIENT_DOCUMENT, "letter.docx", "application/octet-stream", UploadSamples.docx(true),
            "file.upload.macro");
        // Any archive is not a document.
        refused(UploadKind.PATIENT_DOCUMENT, "letter.docx", "application/octet-stream",
            new byte[] {'P', 'K', 0x03, 0x04, 0, 0, 0, 0}, "file.contentMismatch");

        byte[] legacy = new byte[2048];
        System.arraycopy(new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
            (byte) 0xE1}, 0, legacy, 0, 8);
        assertThat(UploadAdmission.vet(file("old.doc", "application/msword", legacy), UploadKind.PATIENT_DOCUMENT)
            .type()).isEqualTo(UploadType.DOC);
        byte[] macroStorage = "_VBA_PROJECT".getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(macroStorage, 0, legacy, 1024, macroStorage.length);
        refused(UploadKind.PATIENT_DOCUMENT, "old.doc", "application/msword", legacy, "file.upload.macro");
    }

    @Test
    @DisplayName("a text file is text to its last byte: markup or binary after a clean head is refused")
    void textIsCheckedToItsEnd() {
        byte[] clean = "Tension 12/8, pouls 72.\n".repeat(400).getBytes(StandardCharsets.UTF_8);
        assertThat(UploadAdmission.vet(file("notes.txt", "text/plain", clean), UploadKind.PATIENT_DOCUMENT).type())
            .isEqualTo(UploadType.TEXT);

        byte[] lateMarkup = ("x".repeat(ContentSniffer.HEAD_BYTES + 10) + "<script>alert(1)</script>")
            .getBytes(StandardCharsets.UTF_8);
        refused(UploadKind.PATIENT_DOCUMENT, "notes.txt", "text/plain", lateMarkup, "file.contentMismatch");

        byte[] lateBinary = Arrays.copyOf("x".repeat(ContentSniffer.HEAD_BYTES + 10)
            .getBytes(StandardCharsets.UTF_8), ContentSniffer.HEAD_BYTES + 20);
        refused(UploadKind.PATIENT_DOCUMENT, "notes.txt", "text/plain", lateBinary, "file.contentMismatch");
    }

    @Test
    @DisplayName("an import takes a CSV file and nothing else")
    void anImportTakesCsvOnly() {
        byte[] csv = "code;libelle;prix\nCS-GEN;Consultation générale;2000\n".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(UploadAdmission.vet(file("grille.csv", "application/vnd.ms-excel", csv), UploadKind.CSV_IMPORT)
            .type()).isEqualTo(UploadType.CSV);
        refused(UploadKind.CSV_IMPORT, "grille.xlsx", "text/csv", UploadSamples.docx(false), "file.upload.csvOnly");
        refused(UploadKind.CSV_IMPORT, "grille.csv", "text/csv", UploadSamples.docx(false), "file.contentMismatch");
        refused(UploadKind.CSV_IMPORT, "grille.csv", "text/csv", UploadSamples.html(), "file.contentMismatch");
    }
}
