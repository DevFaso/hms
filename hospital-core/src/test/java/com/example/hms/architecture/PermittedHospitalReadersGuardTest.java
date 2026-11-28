package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * r81: the caller's permitted hospitals include those where they are registered as a patient.
 * A staff read or write acts only where they work, so a scope is built from
 * {@code ActingScopeResolver.workingHospitalIds} / {@code readableHospitalIds}, never from the raw
 * permitted set. Each read of the raw set (a call or a method reference) is recorded here, per file,
 * with its count and what it is for; one more read anywhere fails until it is decided.
 */
@DisplayName("r81: a scope is never built from the raw permitted hospitals")
class PermittedHospitalReadersGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    /** A call or a method reference: {@code getPermittedHospitalIds()} and {@code ::getPermittedHospitalIds}. */
    private static final Pattern READ = Pattern.compile(
        "(\\.\\s*getPermittedHospitalIds\\s*\\()|(::\\s*getPermittedHospitalIds\\b)");

    /** One reader: how many times it reads the raw set, and what for. */
    private record Reader(int reads, String use) { }

    /** File (relative to the main package) -> its reads of the raw set. A new read in any file fails. */
    private static final Map<String, Reader> READERS = Map.ofEntries(
        entry("security/context/HospitalContextRequestOverrides.java", new Reader(1, "X-Hospital-Id: whether the"
            + " caller may name the hospital at all; a hospital where they are only a patient is then ignored"
            + " outside the portal")),
        entry("security/tenant/ActingScopeResolver.java", new Reader(5, "the source of a request's scope (one"
            + " count), narrowTo (with the same patient-only rule) and workingHospitalIds, the filtered set every"
            + " scope reads")),
        entry("security/tenant/RequestBodyScopeAdvice.java", new Reader(2, "whether a body's hospital is one the"
            + " caller holds, with the same patient-only rule; the hospitals an unnamed body is measured over are"
            + " workingHospitalIds")),
        entry("security/TenantLifecycleGate.java", new Reader(1, "refuses a request when any held hospital is"
            + " suspended: a block, never a scope")),
        entry("fhir/FhirTenantBoundary.java", new Reader(1, "whether the pinned hospital is one the caller holds;"
            + " the pin itself follows the patient-only rule")),
        entry("service/AppointmentServiceImpl.java", new Reader(2, "two debug log lines only")));

    @Test
    @DisplayName("every read of the raw permitted hospitals is recorded, file by file, with its count and use")
    void everyReaderIsRecorded() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.endsWith(Path.of("security/context/HospitalContext.java"))) {
                    continue;
                }
                int reads = reads(SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8)));
                if (reads > 0) {
                    found.put(MAIN.relativize(file).toString().replace('\\', '/'), reads);
                }
            }
        }
        Map<String, Integer> expected = new TreeMap<>();
        READERS.forEach((file, reader) -> expected.put(file, reader.reads()));
        assertThat(found)
            .as("reads of the raw permitted hospitals: build a scope from ActingScopeResolver.workingHospitalIds,"
                + " or record the read (its file, count and use) in READERS" + SourceScan.MANDATE)
            .containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    @DisplayName("the scan counts calls and method references")
    void theScanCountsCallsAndReferences() {
        assertThat(reads("a.getPermittedHospitalIds(); b. getPermittedHospitalIds ();"
            + " map(HospitalContext::getPermittedHospitalIds)")).isEqualTo(3);
        assertThat(reads("getPermittedHospitalIdsOf(x); String s;")).isZero();
    }

    private static int reads(String code) {
        Matcher matcher = READ.matcher(code);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
