package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D923 class, second line of defence: every {@code @PutMapping} /
 * {@code @PatchMapping} handler delegates to a service method that at least
 * one test calls by name.
 *
 * <p>{@link SaveThenRefreshGuardTest} catches the one mechanism D923 had. This
 * test is about the broader class — an update endpoint whose edits are
 * discarded — and asks the weakest question that still has teeth: is the
 * service method behind the handler exercised by any test at all? A handler
 * whose service method no test names is an update nobody has ever watched
 * round-trip. (A mock-based test is not proof the row changed — D923 had one —
 * so a real guard for a given endpoint is a round-trip assertion in an IT; this
 * test only makes sure the endpoint is not entirely dark.)
 *
 * <p>For each handler the scan collects every {@code xxxService.method(} call in
 * its body; each method name must appear as {@code method(} in some file under
 * {@code src/test/java} other than this one (its own fixtures must not count).
 * A handler that calls no {@code *Service} at all — it writes through a
 * repository or a collaborator named otherwise — is reported as
 * {@code -> <none>}: nothing a service test could cover. Handlers listed below
 * are the ones dark today; the list may only shrink.
 */
@DisplayName("Every PUT/PATCH handler's service method is named by a test")
class UpdateHandlerServiceTestCoverageTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms/controller");
    private static final Path TESTS = Paths.get("src/test/java");

    /** A PUT or PATCH mapping annotation, imported or fully qualified. Comments are stripped first. */
    static final Pattern MAPPING = Pattern.compile("@(?:[\\w.]+\\.)?(?:PutMapping|PatchMapping)\\b");

    /**
     * The handler signature once the annotations after the mapping are skipped:
     * {@code [public|protected] <type> name(} — a package-private handler is a handler too.
     */
    static final Pattern SIGNATURE = Pattern.compile("(?:(?:public|protected)\\s+)?[^;{()]*?\\s+(\\w+)\\s*\\(");

    /** {@code something[sS]ervice.method(} inside the handler body. */
    static final Pattern SERVICE_CALL = Pattern.compile("\\b(\\w*[sS]ervice\\w*)\\s*\\.\\s*(\\w+)\\s*\\(");

    private static final String DARK = "no test names this service method; add one that reads the row back after the update";
    private static final String NO_SERVICE =
        "the handler calls no *Service: it writes through a repository or a differently named collaborator,"
            + " so only a controller IT can cover it";

    /** {@code Controller#handler -> service.method} with no test today. Shrink only. */
    private static final Map<String, String> ALLOWED = new TreeMap<>(Map.ofEntries(
        Map.entry("BedManagementController#updateBed -> bedManagementService.updateBed", DARK),
        Map.entry("NotificationController#markAllRead -> notificationService.markAllReadForUser", DARK),
        Map.entry("PatientController#updatePatientAllergy -> patientService.updatePatientAllergy", DARK),
        Map.entry("PatientPortalController#markAllNotificationsRead -> notificationService.markAllReadForUser", DARK),
        Map.entry("PatientPortalController#updateNotificationPreferences -> portalService.updateMyNotificationPreferences", DARK),
        Map.entry("PermissionController#updatePermission -> permissionService.updatePermission", DARK),
        Map.entry("RoleController#setPermissions -> roleService.assignPermissions", DARK),
        Map.entry("OrganizationController#updateOrganization -> <none>.<none>",
            NO_SERVICE + " (the controller loads, checks and saves the Organization through organizationRepository itself)")));

    private record Delegation(int mapping, String handler, String service, String method) {
        String key() {
            return handler + " -> " + service + "." + method;
        }
    }

    /**
     * Every handler of the controller source and the service calls in its body, one
     * group per mapping annotation ({@code mapping} is the annotation's offset, so
     * two overloads of one name stay two handlers).
     */
    static List<Delegation> delegations(String controller, String rawSource) {
        String source = SourceScan.stripComments(rawSource);
        List<Delegation> out = new ArrayList<>();
        Matcher mapping = MAPPING.matcher(source);
        while (mapping.find()) {
            int at = skipAnnotations(source, mapping.end());
            Matcher signature = SIGNATURE.matcher(source).region(at, source.length());
            int params = signature.lookingAt() ? signature.end() - 1 : -1;
            int open = params < 0 ? -1 : source.indexOf('{', SourceScan.closingParen(source, params));
            if (open < 0) {
                // Reported, not skipped: the assertion names the controller and line
                // instead of two integers that disagree.
                String where = controller + ":" + SourceScan.lineOf(source, mapping.start());
                out.add(new Delegation(mapping.start(), where, "<unparsed>", "<unparsed>"));
                continue;
            }
            String handler = controller + "#" + signature.group(1);
            String body = source.substring(open, SourceScan.closingBrace(source, open));
            Set<String> seen = new TreeSet<>();
            Matcher c = SERVICE_CALL.matcher(body);
            while (c.find()) {
                if (seen.add(c.group(1) + "." + c.group(2))) {
                    out.add(new Delegation(mapping.start(), handler, c.group(1), c.group(2)));
                }
            }
            if (seen.isEmpty()) {
                out.add(new Delegation(mapping.start(), handler, "<none>", "<none>"));
            }
        }
        return out;
    }

    /**
     * From the end of the mapping annotation: past its own argument list and every
     * further annotation (with or without arguments), to the first character of the
     * signature. An {@code @Operation(description = "a public API ...")} is skipped
     * whole, so its text cannot be read as the signature.
     */
    static int skipAnnotations(String source, int from) {
        int i = from;
        while (i < source.length()) {
            while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
                i++;
            }
            if (i < source.length() && source.charAt(i) == '(') {
                i = SourceScan.closingParen(source, i) + 1;
            } else if (i < source.length() && source.charAt(i) == '@') {
                i++;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '.'
                    || source.charAt(i) == '_')) {
                    i++;
                }
            } else {
                return i;
            }
        }
        return i;
    }

    /** {@code name(} in test code: every method name some test under src/test/java calls. */
    private static final Pattern CALLED = Pattern.compile("\\b(\\w+)\\s*\\(");

    /** The source-scanning guards: their fixtures spell method names that no test calls. */
    private static final Pattern GUARD_TEST = Pattern.compile(".*(?:GuardTest|CoverageTest|RatchetTest)\\.java");

    /**
     * The names every test calls, read once, so each handler is a set lookup. The
     * guards' own fixtures and every comment are left out: a sentence in a Javadoc
     * or a `save(` inside another guard's fixture string is not a test calling it.
     */
    private static Set<String> namesCalledByTests() throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(TESTS)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (GUARD_TEST.matcher(p.getFileName().toString()).matches()) {
                    continue;
                }
                Matcher m = CALLED.matcher(SourceScan.stripComments(Files.readString(p, StandardCharsets.UTF_8)));
                while (m.find()) {
                    names.add(m.group(1));
                }
            }
        }
        return names;
    }

    private static int countMappings(String rawSource) {
        int n = 0;
        Matcher m = MAPPING.matcher(SourceScan.stripComments(rawSource));
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    @DisplayName("no PUT/PATCH handler delegates to a service method no test names, beyond the listed ones")
    void updateHandlersAreExercised() throws IOException {
        Set<String> called = namesCalledByTests();
        Set<String> dark = new TreeSet<>();
        List<String> unparsed = new ArrayList<>();
        int mappings = 0;
        Set<String> handled = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String controller = p.getFileName().toString().replace(".java", "");
                String source = SourceScan.stripComments(Files.readString(p, StandardCharsets.UTF_8));
                mappings += countMappings(source);
                for (Delegation d : delegations(controller, source)) {
                    handled.add(controller + "@" + d.mapping());
                    if (d.method().equals("<unparsed>")) {
                        unparsed.add(d.handler());
                    } else if (d.method().equals("<none>") || !called.contains(d.method())) {
                        dark.add(d.key());
                    }
                }
            }
        }
        // Every mapping annotation found its handler: the signature scan skipped none.
        assertThat(unparsed).as("PUT/PATCH mappings whose handler signature the scan could not read").isEmpty();
        assertThat(handled).as("one handler per PUT/PATCH mapping").hasSize(mappings);

        List<String> fresh = new ArrayList<>();
        for (String key : dark) {
            if (!ALLOWED.containsKey(key)) {
                fresh.add(key);
            }
        }
        assertThat(fresh)
            .as("an update endpoint no test exercises: add a test that reads the row back after the update (QA D923 class)"
                + SourceScan.MANDATE)
            .isEmpty();

        List<String> stale = new ArrayList<>();
        for (String key : ALLOWED.keySet()) {
            if (!dark.contains(key)) {
                stale.add(key);
            }
        }
        assertThat(stale).as("the handler is now tested: delete its line" + SourceScan.MANDATE).isEmpty();
    }

    @Test
    @DisplayName("the handler scan reads the house controller shapes: multi-line @Operation, overloads, Javadoc mentions")
    void theScanReadsTheHouseShapes() {
        String source = """
            /** Updates. {@code @PutMapping} is mentioned here and must not count. */
            @PutMapping("/{id}")
            @PreAuthorize(APPOINTMENT_MANAGE_ROLES)
            public ResponseEntity<AppointmentResponseDTO> updateProbeVisit(
                @PathVariable UUID id,
                @Valid @RequestBody AppointmentRequestDTO request) {
                Locale locale = parseLocale(lang);
                return ResponseEntity.ok(
                    probeVisitService.updateProbeVisit(id, request, locale, getUsername(authentication)));
            }

            @PatchMapping("/{id}/status")
            @Operation(summary = "Transition", description = "A public API that moves (reschedules) "
                + "the visit; (parens) and {braces} in the text.")
            // a comment before the signature
            public ResponseEntity<Void> patchProbeStatus(@PathVariable UUID id) {
                probeWorkflowService.transitionProbe(id);
                probeAuditService.recordProbe(id);
                return ResponseEntity.noContent().build();
            }

            @org.springframework.web.bind.annotation.PutMapping("/{id}/owner")
            public void reassignProbe(@PathVariable UUID id, @RequestBody Map<String, String> body) {
                probeRepository.save(loadProbe(id));
            }

            @PutMapping("/{id}/owner/{sub}")
            public void reassignProbe(@PathVariable UUID id, @PathVariable UUID sub) {
                probeOwnerService.reassignProbe(id, sub);
            }

            @PatchMapping("/{id}/close")
            ResponseEntity<Void> closeProbe(@PathVariable UUID id) {
                probeCloseService.closeProbe(id);
                return ResponseEntity.noContent().build();
            }

            @GetMapping("/{id}")
            public ResponseEntity<AppointmentResponseDTO> get(@PathVariable UUID id) {
                return ResponseEntity.ok(probeVisitService.getProbeVisit(id));
            }
            """;
        List<Delegation> found = delegations("ProbeController", source);
        assertThat(found).extracting(Delegation::key).containsExactlyInAnyOrder(
            "ProbeController#updateProbeVisit -> probeVisitService.updateProbeVisit",
            "ProbeController#patchProbeStatus -> probeAuditService.recordProbe",
            "ProbeController#patchProbeStatus -> probeWorkflowService.transitionProbe",
            "ProbeController#reassignProbe -> <none>.<none>",
            "ProbeController#reassignProbe -> probeOwnerService.reassignProbe",
            "ProbeController#closeProbe -> probeCloseService.closeProbe");
        assertThat(found.stream().map(Delegation::mapping).distinct().count())
            .as("five mappings, the Javadoc mention not among them, the package-private handler among them")
            .isEqualTo(5);
        assertThat(countMappings(source)).isEqualTo(5);
        assertThat(delegations("X", "@PutMapping(\"/x\")\n    private final Foo foo;\n"))
            .extracting(Delegation::method).containsExactly("<unparsed>");
    }
}
