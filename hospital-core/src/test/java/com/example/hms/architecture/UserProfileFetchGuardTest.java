package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1314: {@code User.patientProfile} and {@code User.staffProfile} are loaded
 * by a unique-key SELECT, forced by {@code @NotFound}. When a query fetches a
 * User, that SELECT runs once per ROW of the result, not once per user: a doctor
 * has no patient row, and a null answer is not remembered. A page of 200 orders
 * ran 200 {@code SELECT ... FROM clinical.patients WHERE user_id = ?} for the
 * same 30 doctors; the MAR ran 5,212. The only thing that avoids it is fetching
 * the two profiles in the query itself ({@code @Fetch(JOIN)} on the mapping does
 * not: tried, measured, 5,212 again).
 *
 * <p>So every {@code @EntityGraph} path or {@code JOIN FETCH} that reaches a User
 * must also fetch its profiles. {@link #NOT_YET} is the debt, counted per file so
 * it can only shrink: a repository off a hot path that D1314 did not touch.
 */
@DisplayName("D1314: a query that fetches a User fetches its profiles")
class UserProfileFetchGuardTest {

    private static final Path REPOSITORIES = Path.of("src/main/java/com/example/hms/repository");

    private static final Pattern GRAPH = Pattern.compile("attributePaths\\s*+=\\s*+\\{([^}]*+)}");
    private static final Pattern PATH = Pattern.compile("\"([^\"]++)\"");
    /** A fetch of a User in JPQL, with the alias it was given. */
    private static final Pattern JPQL_USER = Pattern.compile("FETCH\\s++\\w++\\.(?:user|witnessUser)\\b(?:\\s++(?!LEFT|JOIN|WHERE|FETCH)(\\w++))?");

    /** Files with fetches of a User not yet fetching the profiles (QA D1314 standing debt), and how many. */
    private static final Map<String, Integer> NOT_YET = Map.ofEntries(
        Map.entry("AppointmentRepository.java", 3),
        Map.entry("BillingInvoiceRepository.java", 1),
        Map.entry("BreakGlassSessionRepository.java", 3),
        Map.entry("HospitalRepository.java", 1),
        Map.entry("LabResultRepository.java", 32),
        Map.entry("ProgramEnrollmentRepository.java", 2),
        Map.entry("StaffAvailabilityRepository.java", 1),
        Map.entry("StaffLeaveRequestRepository.java", 1),
        Map.entry("StaffRepository.java", 12),
        Map.entry("StaffShiftRepository.java", 1),
        Map.entry("TreatmentPlanRepository.java", 18),
        Map.entry("TreatmentRepository.java", 3),
        Map.entry("UserRoleHospitalAssignmentRepository.java", 6),
        Map.entry("scheduling/AppointmentSlotRepository.java", 1));

    /** Every fetch of a User in one source that does not fetch the profiles too. */
    static List<String> violations(String source) {
        List<String> found = new ArrayList<>();
        Matcher graph = GRAPH.matcher(source);
        while (graph.find()) {
            List<String> paths = new ArrayList<>();
            Matcher path = PATH.matcher(graph.group(1));
            while (path.find()) {
                paths.add(path.group(1));
            }
            for (String candidate : paths) {
                boolean user = "user".equals(candidate) || candidate.endsWith(".user");
                if (user && !(paths.contains(candidate + ".patientProfile") && paths.contains(candidate + ".staffProfile"))) {
                    found.add("graph path " + candidate);
                }
            }
        }
        Matcher jpql = JPQL_USER.matcher(source);
        while (jpql.find()) {
            String alias = jpql.group(1);
            if (alias == null || !source.contains("FETCH " + alias + ".patientProfile")) {
                found.add("JOIN FETCH " + jpql.group());
            }
        }
        return found;
    }

    @Test
    @DisplayName("every repository, against the debt list: no new one, and a fixed one leaves the list")
    void repositories() throws IOException {
        Map<String, Integer> counted = new TreeMap<>();
        try (Stream<Path> files = Files.walk(REPOSITORIES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                int count = violations(Files.readString(file, StandardCharsets.UTF_8)).size();
                if (count > 0) {
                    counted.put(REPOSITORIES.relativize(file).toString().replace('\\', '/'), count);
                }
            }
        }
        assertThat(counted).isEqualTo(new TreeMap<>(NOT_YET));
    }

    @Test
    @DisplayName("the scan sees a graph and a JOIN FETCH that leave the profiles out")
    void theScanSeesTheShape() {
        assertThat(violations("attributePaths = {\"patient\", \"staff.user\"}")).containsExactly("graph path staff.user");
        assertThat(violations("attributePaths = {\"staff.user\", \"staff.user.patientProfile\", \"staff.user.staffProfile\"}")).isEmpty();
        assertThat(violations("LEFT JOIN FETCH s.user\n WHERE")).hasSize(1);
        assertThat(violations("LEFT JOIN FETCH s.user su\n LEFT JOIN FETCH su.patientProfile\n WHERE")).isEmpty();
    }
}
