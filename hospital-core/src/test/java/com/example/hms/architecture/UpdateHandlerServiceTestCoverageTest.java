package com.example.hms.architecture;

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
 * {@code src/test/java}. Handlers listed below are the ones dark today; the
 * list may only shrink.
 */
@DisplayName("Every PUT/PATCH handler's service method is named by a test")
class UpdateHandlerServiceTestCoverageTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms/controller");
    private static final Path TESTS = Paths.get("src/test/java");

    /** A PUT or PATCH handler (imported or fully qualified): the annotation, any others, the signature, the name. */
    static final Pattern HANDLER = Pattern.compile(
        "@(?:[\\w.]+\\.)?(?:PutMapping|PatchMapping)[^\\n]*\\n(?:\\s*@[^\\n]*\\n)*\\s*public\\s+[^\\n{]*?\\s+(\\w+)\\s*\\(");

    /** {@code something[sS]ervice.method(} inside the handler body. */
    static final Pattern SERVICE_CALL = Pattern.compile("\\b(\\w*[sS]ervice\\w*)\\s*\\.\\s*(\\w+)\\s*\\(");

    private static final String DARK = "no test names this service method; add one that reads the row back after the update";

    /** {@code Controller#handler -> service.method} with no test today. Shrink only. */
    private static final Map<String, String> ALLOWED = new TreeMap<>(Map.of(
        "BedManagementController#updateBed -> bedManagementService.updateBed", DARK,
        "NotificationController#markAllRead -> notificationService.markAllReadForUser", DARK,
        "PatientController#updatePatientAllergy -> patientService.updatePatientAllergy", DARK,
        "PatientPortalController#markAllNotificationsRead -> notificationService.markAllReadForUser", DARK,
        "PatientPortalController#updateNotificationPreferences -> portalService.updateMyNotificationPreferences", DARK,
        "PermissionController#updatePermission -> permissionService.updatePermission", DARK,
        "RoleController#setPermissions -> roleService.assignPermissions", DARK));

    private record Delegation(String handler, String service, String method) {
        String key() {
            return handler + " -> " + service + "." + method;
        }
    }

    /** Every handler of the controller source and the service calls in its body. */
    static List<Delegation> delegations(String controller, String source) {
        List<Delegation> out = new ArrayList<>();
        Matcher h = HANDLER.matcher(source);
        while (h.find()) {
            int open = source.indexOf('{', h.end());
            if (open < 0) {
                continue;
            }
            String body = source.substring(open, closingBrace(source, open));
            Set<String> seen = new TreeSet<>();
            Matcher c = SERVICE_CALL.matcher(body);
            while (c.find()) {
                if (seen.add(c.group(1) + "." + c.group(2))) {
                    out.add(new Delegation(controller + "#" + h.group(1), c.group(1), c.group(2)));
                }
            }
            if (seen.isEmpty()) {
                out.add(new Delegation(controller + "#" + h.group(1), "<none>", "<none>"));
            }
        }
        return out;
    }

    private static int closingBrace(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return i;
            }
        }
        return source.length();
    }

    private static String allTestSources() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(TESTS)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                all.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return all.toString();
    }

    @Test
    @DisplayName("no PUT/PATCH handler delegates to a service method no test names, beyond the listed ones")
    void updateHandlersAreExercised() throws IOException {
        String tests = allTestSources();
        Set<String> dark = new TreeSet<>();
        int handlers = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String controller = p.getFileName().toString().replace(".java", "");
                List<Delegation> found = delegations(controller, Files.readString(p, StandardCharsets.UTF_8));
                handlers += found.stream().map(Delegation::handler).distinct().count();
                for (Delegation d : found) {
                    if (d.method().equals("<none>")) {
                        continue;
                    }
                    if (!Pattern.compile("\\b" + Pattern.quote(d.method()) + "\\s*\\(").matcher(tests).find()) {
                        dark.add(d.key());
                    }
                }
            }
        }
        assertThat(handlers).as("the handler scan found the controllers").isGreaterThan(100);

        List<String> fresh = new ArrayList<>();
        for (String key : dark) {
            if (!ALLOWED.containsKey(key)) {
                fresh.add(key);
            }
        }
        assertThat(fresh)
            .as("an update endpoint no test exercises: add a test that reads the row back after the update (QA D923 class)")
            .isEmpty();

        List<String> stale = new ArrayList<>();
        for (String key : ALLOWED.keySet()) {
            if (!dark.contains(key)) {
                stale.add(key);
            }
        }
        assertThat(stale).as("the handler is now tested: delete its line").isEmpty();
    }

    @Test
    @DisplayName("the handler scan reads the house controller shapes")
    void theScanReadsTheHouseShapes() {
        String source = """
            @PutMapping("/{id}")
            @PreAuthorize(APPOINTMENT_MANAGE_ROLES)
            public ResponseEntity<AppointmentResponseDTO> updateAppointment(
                @PathVariable UUID id,
                @Valid @RequestBody AppointmentRequestDTO request) {
                Locale locale = parseLocale(lang);
                return ResponseEntity.ok(
                    appointmentService.updateAppointment(id, request, locale, getUsername(authentication)));
            }

            @PatchMapping("/{id}/status")
            public ResponseEntity<Void> patchStatus(@PathVariable UUID id) {
                encounterWorkflowService.transition(id);
                auditService.record(id);
                return ResponseEntity.noContent().build();
            }

            @GetMapping("/{id}")
            public ResponseEntity<AppointmentResponseDTO> get(@PathVariable UUID id) {
                return ResponseEntity.ok(appointmentService.getAppointment(id));
            }
            """;
        assertThat(delegations("AppointmentController", source)).extracting(Delegation::key).containsExactlyInAnyOrder(
            "AppointmentController#updateAppointment -> appointmentService.updateAppointment",
            "AppointmentController#patchStatus -> auditService.record",
            "AppointmentController#patchStatus -> encounterWorkflowService.transition");
    }
}
