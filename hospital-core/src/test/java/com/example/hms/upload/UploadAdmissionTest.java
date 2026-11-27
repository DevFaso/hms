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
    @DisplayName("a photo path takes any accepted image by its bytes, whatever its name, under the image's extension")
    void aPhotoIsTypedByItsBytesAlone() {
        // What a phone gallery hands over: a WebP or a GIF under the name the app gave it.
        for (UploadKind kind : new UploadKind[] {UploadKind.PROFILE_IMAGE, UploadKind.PATIENT_PHOTO,
            UploadKind.CHAT_PHOTO}) {
            UploadAdmission.VettedUpload webp = UploadAdmission.vet(
                file("profile_photo.jpg", "image/jpeg", UploadSamples.webp()), kind);
            assertThat(webp.type()).as("%s", kind).isEqualTo(UploadType.WEBP);
            assertThat(webp.mediaType()).isEqualTo("image/webp");
            assertThat(webp.extension()).isEqualTo(".webp");

            assertThat(UploadAdmission.vet(file("photo", "image/jpeg", UploadSamples.png()), kind).extension())
                .as("%s, no extension", kind).isEqualTo(".png");
            assertThat(UploadAdmission.vet(file("photo.jpeg", "image/jpeg", UploadSamples.jpeg()), kind).extension())
                .as("%s keeps a right extension", kind).isEqualTo(".jpeg");
        }
        assertThat(UploadAdmission.vet(file("profile_photo.jpg", "image/jpeg", UploadSamples.gif()),
            UploadKind.PROFILE_IMAGE).extension()).isEqualTo(".gif");
        // The chat upload endpoint takes a photo or a voice memo: the photo by its bytes, the memo by name too.
        assertThat(UploadAdmission.vet(file("photo.jpg", "image/jpeg", UploadSamples.webp()),
            UploadKind.CHAT_ATTACHMENT).type()).isEqualTo(UploadType.WEBP);
        refused(UploadKind.CHAT_ATTACHMENT, "memo.mp3", "audio/mpeg", UploadSamples.ogg(), "file.contentMismatch");
        // A type the path does not take stays refused, under any name.
        refused(UploadKind.CHAT_PHOTO, "photo.jpg", "image/jpeg", UploadSamples.gif(), "file.contentMismatch");
        refused(UploadKind.PROFILE_IMAGE, "profile_photo.jpg", "image/jpeg", UploadSamples.pdf(),
            "file.contentMismatch");
        byte[] heif = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0};
        refused(UploadKind.PROFILE_IMAGE, "profile_photo.jpg", "image/jpeg", heif, "file.contentMismatch");
        refused(UploadKind.PATIENT_PHOTO, "photo", "image/jpeg", UploadSamples.svg(),
            "patient.photo.typeUnsupported");
    }

    @Test
    @DisplayName("UTF-16 text is text, not audio: its byte-order mark is no MPEG frame")
    void utf16TextIsAccepted() {
        for (java.nio.charset.Charset charset : new java.nio.charset.Charset[] {StandardCharsets.UTF_16LE,
            StandardCharsets.UTF_16BE}) {
            byte[] mark = charset == StandardCharsets.UTF_16LE
                ? new byte[] {(byte) 0xFF, (byte) 0xFE} : new byte[] {(byte) 0xFE, (byte) 0xFF};
            byte[] text = "code;libelle;prix\nCS-GEN;Consultation générale;2000\n".getBytes(charset);
            byte[] content = Arrays.copyOf(mark, mark.length + text.length);
            System.arraycopy(text, 0, content, mark.length, text.length);

            assertThat(UploadAdmission.vet(file("notes.txt", "text/plain", content), UploadKind.PATIENT_DOCUMENT)
                .type()).as("%s .txt", charset).isEqualTo(UploadType.TEXT);
            assertThat(UploadAdmission.vet(file("grille.csv", "text/csv", content), UploadKind.CSV_IMPORT).type())
                .as("%s .csv", charset).isEqualTo(UploadType.CSV);
            refused(UploadKind.CHAT_AUDIO, "memo.mp3", "audio/mpeg", content, "file.contentMismatch");
        }
        assertThat(ContentSniffer.detect(new byte[] {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64, 0, 0}))
            .contains(UploadType.MP3);
        byte[] utf16Markup = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_16LE);
        byte[] marked = new byte[utf16Markup.length + 2];
        marked[0] = (byte) 0xFF;
        marked[1] = (byte) 0xFE;
        System.arraycopy(utf16Markup, 0, marked, 2, utf16Markup.length);
        refused(UploadKind.PATIENT_DOCUMENT, "notes.txt", "text/plain", marked, "file.contentMismatch");
    }

    @Test
    @DisplayName("content of one accepted type under the name of another is refused")
    void theNameMustAnnounceTheContent() {
        refused(UploadKind.IMAGING_IMAGE, "x.png", "image/png", UploadSamples.jpeg(), "file.contentMismatch");
        refused(UploadKind.PATIENT_DOCUMENT, "scan.jpg", "image/jpeg", UploadSamples.png(), "file.contentMismatch");
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
    @DisplayName("text uploads are checked for markup more strictly: any tag is refused, a « < » in a value is text")
    void anyTagIsMarkupInText() {
        for (String tagged : new String[] {"Voir <p>compte rendu</p>", "<a href=x>lien</a>", "note <!-- x -->",
            "<?php echo 1; ?>", "fin </div>", "<Style>x</Style>", "<img src=x>"}) {
            byte[] content = tagged.getBytes(StandardCharsets.UTF_8);
            refused(UploadKind.PATIENT_DOCUMENT, "notes.txt", "text/plain", content, "file.contentMismatch");
            refused(UploadKind.CHART_ATTACHMENT, "notes.txt", "text/plain", content, "file.contentMismatch");
            refused(UploadKind.CSV_IMPORT, "grille.csv", "text/csv", content, "file.contentMismatch");
        }
        byte[] lateTag = ("x".repeat(ContentSniffer.HEAD_BYTES + 10) + "<b>").getBytes(StandardCharsets.UTF_8);
        refused(UploadKind.PATIENT_DOCUMENT, "notes.txt", "text/plain", lateTag, "file.contentMismatch");

        byte[] clinical = "TA <120/80, glycémie < 1,1 g/l, a<=b, 3 <5 ans, fin <".getBytes(StandardCharsets.UTF_8);
        assertThat(UploadAdmission.vet(file("notes.txt", "text/plain", clinical), UploadKind.PATIENT_DOCUMENT).type())
            .isEqualTo(UploadType.TEXT);
        assertThat(UploadAdmission.vet(file("grille.csv", "text/csv", clinical), UploadKind.CSV_IMPORT).type())
            .isEqualTo(UploadType.CSV);
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
