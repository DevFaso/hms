package com.example.hms.architecture;

import com.example.hms.testsupport.GuardAllowlist;
import com.example.hms.testsupport.GuardAllowlist.Allowance;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1632 guard: an audit row filed under the wrong role.
 *
 * <p>A doctor who is also a lab director released a lab result signed in as
 * lab director; the row read {@code roleName: DOCTOR} because every audit
 * writer anchored on {@code findFirstByUser_IdAndHospital_IdAndActiveTrue},
 * which returns ANY active assignment at the hospital. A source file that
 * writes audit rows ({@code AuditEventRequestDTO}) resolves the actor's
 * assignment through {@code ActingAssignments}, which picks the one of the
 * role the request acts under.
 */
@DisplayName("Audit writers resolve the assignment of the acting role, never the first active one")
class AuditActingRoleGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Pattern FIRST_ACTIVE = Pattern.compile("\\.findFirstByUser_IdAndHospital_IdAndActiveTrue\\(");

    /** Per file. Shrink only. */
    private static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow(
        "LabResultServiceImpl", 1, "resolveActorDisplay reads the user's display NAME, the same on every row; no role");

    @Test
    @DisplayName("no file that builds an AuditEventRequestDTO anchors on findFirstByUser_IdAndHospital_IdAndActiveTrue")
    void auditWritersUseTheActingRole() throws IOException {
        List<Path> javaFiles;
        try (Stream<Path> files = Files.walk(MAIN)) {
            javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        Map<String, Integer> actual = new TreeMap<>();
        int writers = 0;
        for (Path file : javaFiles) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (!source.contains("AuditEventRequestDTO.builder()")) {
                continue;
            }
            writers++;
            Matcher m = FIRST_ACTIVE.matcher(source);
            while (m.find()) {
                actual.merge(file.getFileName().toString().replace(".java", ""), 1, Integer::sum);
            }
        }
        assertThat(writers).as("the scan found the audit writers").isGreaterThan(10);
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("an audit writer files the first active assignment's role, not the acting one (QA D1632)"
                + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED)).as("the site was fixed: delete its line").isEmpty();
    }
}
