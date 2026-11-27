package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * r81: the caller's permitted hospitals include those where they are registered as a patient.
 * A staff read or write acts only where they work, so a scope is built from
 * {@code ActingScopeResolver.workingHospitalIds} / {@code readableHospitalIds}, never from the raw
 * permitted set. Each reader of the raw set is recorded here with what it uses it for; a new one
 * fails until it is decided.
 */
@DisplayName("r81: a scope is never built from the raw permitted hospitals")
class PermittedHospitalReadersGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final String CALL = "getPermittedHospitalIds()";

    /** File (relative to the main package) -> what it reads the raw set for. */
    private static final Map<String, String> READERS = Map.ofEntries(
        entry("security/context/HospitalContextRequestOverrides.java", "X-Hospital-Id: whether the caller may name"
            + " the hospital at all; a hospital where they are only a patient is then ignored outside the portal"),
        entry("security/tenant/ActingScopeResolver.java", "the producer, narrowTo (with the same patient-only rule) and"
            + " workingHospitalIds, the filtered set every scope reads"),
        entry("security/tenant/RequestBodyScopeAdvice.java", "whether a body's hospital is one the caller holds,"
            + " with the same patient-only rule; the hospitals an unnamed body is measured over are"
            + " workingHospitalIds"),
        entry("security/TenantLifecycleGate.java", "refuses a request when any held hospital is suspended: a block,"
            + " never a scope"),
        entry("fhir/FhirTenantBoundary.java", "whether the pinned hospital is one the caller holds; the pin itself"
            + " follows the patient-only rule"),
        entry("service/AppointmentServiceImpl.java", "a debug log line only"));

    @Test
    @DisplayName("every reader of the raw permitted hospitals is recorded with its use")
    void everyReaderIsRecorded() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (code.contains(CALL) && !file.endsWith(Path.of("security/context/HospitalContext.java"))) {
                    found.add(MAIN.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        assertThat(found)
            .as("readers of the raw permitted hospitals: build a scope from ActingScopeResolver.workingHospitalIds,"
                + " or record the use in READERS" + SourceScan.MANDATE)
            .containsExactlyInAnyOrderElementsOf(new TreeMap<>(READERS).keySet());
    }
}
