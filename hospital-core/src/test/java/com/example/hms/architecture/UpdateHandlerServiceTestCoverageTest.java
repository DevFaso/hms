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

    /**
     * Method names so common that their bare appearance in some test proves nothing
     * ({@code repository.update(}, another service's {@code cancel(}): for these the
     * test must call the SAME {@code service.method} pair the handler calls.
     */
    static final Set<String> GENERIC_NAMES = Set.of(
        "update", "create", "delete", "save", "cancel", "close", "assign", "archive", "apply", "run", "send", "set",
        "get", "find", "list", "remove", "add", "process", "handle", "execute", "complete", "start", "stop", "reset",
        "activate", "deactivate", "approve", "reject", "submit", "transition", "move", "mark", "toggle", "sync");

    /** {@code Controller#handler -> service.method} with no test today. Shrink only. */
    private static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow(
        "AdvanceDirectiveController#update -> advanceDirectiveService.update", 1,
            DARK + " (a generic name: no test of AdvanceDirectiveService calls update)",
        "BedManagementController#updateBed -> bedManagementService.updateBed", 1, DARK,
        "NotificationController#markAllRead -> notificationService.markAllReadForUser", 1, DARK,
        "PatientController#updatePatientAllergy -> patientService.updatePatientAllergy", 1, DARK,
        "PatientPortalController#markAllNotificationsRead -> notificationService.markAllReadForUser", 1, DARK,
        "PatientPortalController#updateNotificationPreferences -> portalService.updateMyNotificationPreferences", 1, DARK,
        "PermissionController#updatePermission -> permissionService.updatePermission", 1, DARK,
        "RoleController#setPermissions -> roleService.assignPermissions", 1, DARK,
        "OrganizationController#updateOrganization -> <none>.<none>", 1,
            NO_SERVICE + " (the controller loads, checks and saves the Organization through organizationRepository itself)");

    private record Delegation(int mapping, String handler, String service, String serviceType, String method) {
        String key() {
            return handler + " -> " + service + "." + method;
        }
    }

    /** The declared type of a field: {@code private final AdvanceDirectiveService advanceDirectiveService;}. */
    static String fieldType(String source, String field) {
        Matcher m = Pattern.compile("\\b([A-Z]\\w*)\\s+" + Pattern.quote(field) + "\\s*[;=]").matcher(source);
        return m.find() ? m.group(1) : "<unknown>";
    }

    /**
     * Every handler of the controller source (comments already stripped) and the
     * service calls in its body, one group per mapping annotation ({@code mapping} is
     * the annotation's offset, so two overloads of one name stay two handlers).
     */
    static List<Delegation> delegations(String controller, String source) {
        List<Delegation> out = new ArrayList<>();
        Matcher mapping = MAPPING.matcher(source);
        while (mapping.find()) {
            int at = SourceScan.skipAnnotations(source, mapping.end());
            Matcher signature = SIGNATURE.matcher(source).region(at, source.length());
            int params = signature.lookingAt() ? signature.end() - 1 : -1;
            int open = params < 0 ? -1 : source.indexOf('{', SourceScan.closingParen(source, params));
            if (open < 0) {
                // Reported, not skipped: the assertion names the controller and line
                // instead of two integers that disagree.
                String where = controller + ":" + SourceScan.lineOf(source, mapping.start());
                out.add(new Delegation(mapping.start(), where, "<unparsed>", "<none>", "<unparsed>"));
                continue;
            }
            String handler = controller + "#" + signature.group(1);
            String body = source.substring(open, SourceScan.closingBrace(source, open));
            Set<String> seen = new TreeSet<>();
            Matcher c = SERVICE_CALL.matcher(body);
            while (c.find()) {
                if (seen.add(c.group(1) + "." + c.group(2))) {
                    out.add(new Delegation(mapping.start(), handler, c.group(1), fieldType(source, c.group(1)), c.group(2)));
                }
            }
            if (seen.isEmpty()) {
                out.add(new Delegation(mapping.start(), handler, "<none>", "<none>", "<none>"));
            }
        }
        return out;
    }

    /** {@code name(} in test code: every method name some test under src/test/java calls. */
    private static final Pattern CALLED = Pattern.compile("\\b(\\w+)\\s*\\(");

    /** The source-scanning guards: their fixtures spell method names that no test calls. */
    private static final Pattern GUARD_TEST = Pattern.compile(".*(?:GuardTest|CoverageTest|RatchetTest)\\.java");

    /**
     * A Mockito stub or verification: {@code when(service.update(…))}, {@code verify(service).update(…)},
     * {@code given(…)}, {@code doReturn(x).when(service).update(…)}. The method named inside is
     * mocked, not exercised — D923 had exactly such a slice test — so these regions are blanked
     * before the names are collected.
     */
    private static final Pattern STUB_CALL = Pattern.compile("\\b(?:when|given|verify|verifyNoMoreInteractions|inOrder)\\(");

    /**
     * A method DECLARATION in test code ({@code @Test void updatePermission() {}}): the
     * name is the test's, not a call to the service, and must not count as one.
     */
    private static final Pattern DECLARATION = Pattern.compile(
        "(?m)^\\s*(?:(?:public|private|protected|static|final|default)\\s+)*(?:void|[\\w<>\\[\\],.? ]+?)\\s+(\\w+)\\s*\\([^;{)]*\\)\\s*(?:throws[^{;]*)?\\{");

    static String withoutStubs(String source) {
        StringBuilder out = new StringBuilder(source);
        Matcher declaration = DECLARATION.matcher(source);
        while (declaration.find()) {
            for (int i = declaration.start(1); i < declaration.end(1); i++) {
                out.setCharAt(i, ' ');
            }
        }
        Matcher m = STUB_CALL.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingParen(source, open);
            for (int i = open + 1; i < close && i < out.length(); i++) {
                if (out.charAt(i) != '\n') {
                    out.setCharAt(i, ' ');
                }
            }
            // verify(service).update(...): the method after the closing paren is the stubbed one.
            int after = close + 1;
            while (after < source.length() && Character.isWhitespace(source.charAt(after))) {
                after++;
            }
            if (after < source.length() && source.charAt(after) == '.') {
                int nameEnd = after + 1;
                while (nameEnd < source.length() && Character.isLetterOrDigit(source.charAt(nameEnd))) {
                    nameEnd++;
                }
                for (int i = after; i < nameEnd; i++) {
                    out.setCharAt(i, ' ');
                }
            }
        }
        return out.toString();
    }

    /**
     * The names every test calls, read once, so each handler is a set lookup. The
     * guards' own fixtures, every comment and every Mockito stub are left out: a
     * sentence in a Javadoc, a `save(` inside another guard's fixture string, or a
     * `when(service.updateFoo(…))` is not a test exercising the method.
     */
    private static Set<String> namesCalledByTests() throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(TESTS)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (GUARD_TEST.matcher(p.getFileName().toString()).matches()) {
                    continue;
                }
                String calls = withoutStubs(SourceScan.stripComments(Files.readString(p, StandardCharsets.UTF_8)));
                Matcher m = CALLED.matcher(calls);
                while (m.find()) {
                    names.add(m.group(1));
                }
                // The `service.method` pairs too: a generic name is covered only by its own
                // pair — by the field name, or by the service TYPE the test file names (a
                // service test calls `service.update(` on a mock typed AdvanceDirectiveService).
                Matcher pair = SERVICE_CALL.matcher(calls);
                Set<String> methods = new TreeSet<>();
                while (pair.find()) {
                    names.add(pair.group(1) + "." + pair.group(2));
                    methods.add(pair.group(2));
                }
                Matcher type = SERVICE_TYPE.matcher(calls);
                Set<String> types = new TreeSet<>();
                while (type.find()) {
                    types.add(type.group(1));
                }
                for (String t : types) {
                    for (String method : methods) {
                        names.add(t + "." + method);
                    }
                }
            }
        }
        return names;
    }

    /** A service type a test file names: {@code AdvanceDirectiveService}, {@code AdvanceDirectiveServiceImpl}. */
    private static final Pattern SERVICE_TYPE = Pattern.compile("\\b([A-Z]\\w*Service)(?:Impl)?\\b");

    /**
     * A handler's delegation is exercised when a test calls the same {@code service.method}
     * pair (by the field's name or its declared type), or — for a name specific enough to
     * mean one thing — the method by name.
     */
    static boolean covered(Set<String> called, Delegation d) {
        return called.contains(d.service() + "." + d.method())
            || called.contains(d.serviceType() + "." + d.method())
            || (!GENERIC_NAMES.contains(d.method()) && called.contains(d.method()));
    }

    /** The PUT/PATCH mapping annotations of a source whose comments are already stripped. */
    private static int countMappings(String source) {
        int n = 0;
        Matcher m = MAPPING.matcher(source);
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
                    } else if (d.method().equals("<none>") || !covered(called, d)) {
                        dark.add(d.key());
                    }
                }
            }
        }
        // Every mapping annotation found its handler: the signature scan skipped none.
        assertThat(unparsed).as("PUT/PATCH mappings whose handler signature the scan could not read").isEmpty();
        assertThat(handled).as("one handler per PUT/PATCH mapping").hasSize(mappings);

        Map<String, Integer> actual = GuardAllowlist.counts(dark);
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("an update endpoint no test exercises: add a test that reads the row back after the update (QA D923 class)"
                + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED))
            .as("the handler is now tested: delete its line" + SourceScan.MANDATE)
            .isEmpty();
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
        List<Delegation> found = delegations("ProbeController", SourceScan.stripComments(source));
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
        assertThat(countMappings(SourceScan.stripComments(source))).isEqualTo(5);
        assertThat(delegations("X", "@PutMapping(\"/x\")\n    private final Foo foo;\n"))
            .extracting(Delegation::method).containsExactly("<unparsed>");
    }

    @Test
    @DisplayName("a Mockito stub or verification does not count as a test calling the method")
    void stubsAreNotCalls() {
        String test = """
            when(fooService.updateFoo(any(), any())).thenReturn(dto);
            given(barService.updateBar(id)).willReturn(bar);
            verify(fooService).updateFoo(id, request);
            doReturn(x).when(bazService).updateBaz(id);
            FooResponse saved = fooService.renameFoo(id, request);
            @Test
            void updateQux() {
                mockMvc.perform(put("/qux/1")).andExpect(status().isOk());
            }
            """;
        String kept = withoutStubs(test);
        assertThat(kept).contains("renameFoo(", "perform(")
            .doesNotContain("updateFoo(", "updateBar(", "updateBaz(", "updateQux(");
    }

    @Test
    @DisplayName("a generic method name is covered only by a test calling the same service.method pair")
    void genericNamesNeedTheirPair() {
        Set<String> called = Set.of("update", "repository.update", "renameWard", "wardService.renameWard");
        Delegation rename = new Delegation(0, "WardController#rename", "wardService", "WardService", "renameWard");
        Delegation update = new Delegation(0, "WardController#update", "wardService", "WardService", "update");
        assertThat(covered(called, rename)).isTrue();
        assertThat(covered(called, update)).isFalse();
        assertThat(covered(Set.of("wardService.update"), update)).isTrue();
        // A service test names the TYPE and calls the method on a mock named otherwise.
        assertThat(covered(Set.of("WardService.update", "service.update"), update)).isTrue();
        assertThat(fieldType("private final WardService wardService;\n", "wardService")).isEqualTo("WardService");
    }
}
