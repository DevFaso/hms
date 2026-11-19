package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uploaded files go through the storage port ({@code com.example.hms.storage.FileStorage}),
 * so the same code runs on a local directory and on the private bucket. A
 * class that writes a file itself puts it on the container's disk, which a
 * deploy replaces once the volume is gone (that loss is what the port ends).
 *
 * <p>Two rules over hospital-core's main sources:
 * <ol>
 *   <li>only the classes listed in {@link #ALLOWED_FILE_WRITERS}, each with
 *       its reason, call a {@code java.nio.file.Files} write, copy, move,
 *       delete or directory-creation method (or open a file output stream);</li>
 *   <li>only the storage adapters (and the start-up volume check) read the
 *       upload directory settings, so no class can build its own path into
 *       the upload tree.</li>
 * </ol>
 * A new class that needs a file of its own adds itself to the list with a
 * reason a reviewer can check; anything kept for a user belongs in the port.
 */
@DisplayName("Uploaded files are written only through the storage port")
class UploadStorageWriteGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final Pattern FILE_WRITE = Pattern.compile(
        "Files\\s*\\.\\s*(?:write|writeString|copy|move|newOutputStream|newBufferedWriter|createDirectories"
            + "|createDirectory|createFile|createTempFile|delete|deleteIfExists)\\s*\\("
            + "|new\\s+(?:java\\.io\\.)?(?:FileOutputStream|FileWriter|RandomAccessFile)\\s*\\("
            + "|\\.transferTo\\s*\\(");

    private static final Pattern UPLOAD_DIR_SETTING = Pattern.compile("app\\.upload\\.dir|app\\.patient-photo\\.dir");

    /** Path under com/example/hms -> why it may write files itself. */
    private static final Map<String, String> ALLOWED_FILE_WRITERS = Map.of(
        "storage/LocalFileStorage.java",
            "the local adapter of the port: the one class that writes under the upload directory",
        "fhir/bulk/FhirBulkExportRunner.java",
            "stages NDJSON in its scratch directory while a job runs, then publishes it to the port",
        "service/scheduled/TenantPurgeExecutor.java",
            "tenant purge archive in hms.tenant-archive.output-dir (temp directory), not a user upload",
        "service/tenant/TenantArchiveEncryptionServiceImpl.java",
            "encrypts that tenant purge archive in place, beside it",
        "service/tenant/TenantExportPackager.java",
            "zips that tenant purge archive");

    /** Path under com/example/hms -> why it may read the upload directory setting. */
    private static final Map<String, String> ALLOWED_UPLOAD_DIR_READERS = Map.of(
        "storage/FileStorageConfig.java", "builds the local adapter (and the bucket's cut-over fallback) on it",
        "config/UploadStoragePersistenceCheck.java", "warns at start-up when the local directory is not persistent");

    @Test
    @DisplayName("only the listed classes write files, and each listed class still does")
    void onlyTheListedClassesWriteFiles() throws IOException {
        TreeSet<String> writers = classesMatching(FILE_WRITE);

        assertThat(writers)
            .as("A class writes files itself. Uploaded files go through FileStorage; anything else "
                + "is added to ALLOWED_FILE_WRITERS with its reason.")
            .isSubsetOf(ALLOWED_FILE_WRITERS.keySet());
        assertThat(ALLOWED_FILE_WRITERS.keySet())
            .as("A listed class no longer writes files: remove it from the list.")
            .isSubsetOf(writers);
    }

    @Test
    @DisplayName("only the storage adapters read the upload directory settings")
    void onlyTheAdaptersReadTheUploadDirectory() throws IOException {
        assertThat(classesMatching(UPLOAD_DIR_SETTING))
            .as("A class reads app.upload.dir / app.patient-photo.dir: build no path into the "
                + "upload tree, use FileStorage.")
            .isSubsetOf(ALLOWED_UPLOAD_DIR_READERS.keySet());
    }

    private static TreeSet<String> classesMatching(Pattern pattern) throws IOException {
        Path root = MAIN.resolve("com/example/hms");
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
        }
        TreeSet<String> matches = new TreeSet<>();
        for (Path source : sources) {
            String code = stripComments(Files.readString(source));
            if (pattern.matcher(code).find()) {
                matches.add(root.relativize(source).toString().replace('\\', '/'));
            }
        }
        return matches;
    }

    /** Comments may name the APIs (this guard's own references do); only code counts. */
    private static String stripComments(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
    }
}
