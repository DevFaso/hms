package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every response that carries a stored upload is built by
 * {@code UploadResponses}: the media type derived from the content when the
 * file was stored (anything else leaves as opaque bytes),
 * {@code X-Content-Type-Options: nosniff}, and a {@code Content-Disposition}
 * that is an attachment unless the path is a vetted viewer of raster images
 * and PDFs. A controller that types such a response itself can serve a stored
 * type a client once declared, in the page.
 *
 * <p>The scan: each main class that builds a {@code ResponseEntity} and
 * handles a stored upload (a stream out of the storage port, or one of the
 * payloads the upload services return). A class that streams something the
 * server wrote itself is listed with its reason.
 */
@DisplayName("A stored upload is served only through UploadResponses")
class UploadServingGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final Pattern STORED_UPLOAD = Pattern.compile(
        "StoredObjectResource|PhotoPayload|DocumentPayload|ChatAttachmentPayload|ImageContent\\b");

    /** Path under com/example/hms -> why it serves stored bytes without UploadResponses. */
    private static final Map<String, String> SERVER_WRITTEN = Map.of(
        "fhir/bulk/FhirBulkExportStatusController.java",
            "streams the NDJSON files the export job wrote itself, never an upload; always an attachment");

    @Test
    @DisplayName("every controller that streams a stored upload builds the response with UploadResponses")
    void storedUploadsLeaveThroughUploadResponses() throws IOException {
        TreeSet<String> servers = new TreeSet<>();
        TreeSet<String> byHand = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path source : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = UploadStorageWriteGuardTest.codeOnly(Files.readString(source));
                if (!code.contains("ResponseEntity") || !STORED_UPLOAD.matcher(code).find()) {
                    continue;
                }
                String path = MAIN.relativize(source).toString().replace('\\', '/');
                servers.add(path);
                if (!code.contains("UploadResponses.attachment(") && !code.contains("UploadResponses.viewer(")) {
                    byHand.add(path);
                }
            }
        }

        assertThat(servers).as("the classes that serve stored uploads were found").contains(
            "controller/PatientPhotoController.java", "controller/ProfileImageController.java",
            "controller/ImagingStudyImageController.java", "controller/PatientImagingPortalController.java",
            "controller/PatientPortalController.java", "controller/PatientDocumentStaffController.java",
            "controller/ChatController.java");
        assertThat(byHand)
            .as("A class streams a stored upload and types the response itself. Build it with "
                + "UploadResponses.attachment(storedType, name), or UploadResponses.viewer(...) on a path whose "
                + "viewer shows images and PDFs; a class that streams what the server wrote itself is listed in "
                + "SERVER_WRITTEN with its reason.")
            .isSubsetOf(SERVER_WRITTEN.keySet());
        assertThat(SERVER_WRITTEN.keySet())
            .as("A listed class no longer serves stored bytes by hand: remove it from the list.")
            .isSubsetOf(byHand);
    }

    @Test
    @DisplayName("no controller of a stored upload sets its type or its disposition beside UploadResponses")
    void noHandTypedResponseBesideUploadResponses() throws IOException {
        TreeSet<String> offenders = new TreeSet<>();
        for (String controller : new String[] {"PatientPhotoController", "ProfileImageController",
            "ImagingStudyImageController", "PatientImagingPortalController", "PatientDocumentStaffController",
            "ChatController"}) {
            String code = UploadStorageWriteGuardTest.codeOnly(
                Files.readString(MAIN.resolve("controller/" + controller + ".java")));
            if (code.contains("ContentDisposition.inline()") || code.contains("MediaType.parseMediaType(")
                    || code.contains("CONTENT_DISPOSITION")) {
                offenders.add(controller);
            }
        }

        assertThat(offenders)
            .as("These controllers serve nothing but stored uploads: a type or a disposition set by hand in one "
                + "of them goes around UploadResponses.")
            .isEmpty();
    }
}
