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
    static final Pattern SIGNATURE = Pattern.compile("[^;{()]*+\\(");

    /** {@code something[sS]ervice.method(} inside the handler body. */
    static final Pattern SERVICE_CALL = Pattern.compile(
        "\\b((?:[a-rt-zA-RT-Z0-9_]|[sS](?!ervice))*+[sS]ervice\\w*+)\\s*+\\.\\s*+(\\w++)\\s*+\\(");

    /**
     * The handler's name in its signature head (the text up to its parameter list):
     * the last word, preceded by whitespace; null when the head is not a signature.
     */
    static String handlerName(String head) {
        int end = head.length();
        while (end > 0 && Character.isWhitespace(head.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && isWordChar(head.charAt(start - 1))) {
            start--;
        }
        if (start == end || start == 0 || !Character.isWhitespace(head.charAt(start - 1))) {
            return null;
        }
        return head.substring(start, end);
    }

    private static boolean isWordChar(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_';
    }

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
            String name = signature.lookingAt() ? handlerName(source.substring(at, signature.end() - 1)) : null;
            int params = name == null ? -1 : signature.end() - 1;
            int open = params < 0 ? -1 : source.indexOf('{', SourceScan.closingParen(source, params));
            if (open < 0) {
                // Reported, not skipped: the assertion names the controller and line
                // instead of two integers that disagree.
                String where = controller + ":" + SourceScan.lineOf(source, mapping.start());
                out.add(new Delegation(mapping.start(), where, "<unparsed>", "<none>", "<unparsed>"));
                continue;
            }
            String handler = controller + "#" + name;
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

    /**
     * The test source with the CONTENT of every string literal and text block blanked
     * (delimiters and newlines kept). A source-scanning guard spells method names in its
     * fixtures ({@code """ … service.update(…) … """}) that no test calls; blanking the
     * literals drops exactly those, so no file has to be left out by its name — a
     * behaviour test named {@code AdmissionCreationGuardTest}, or a service test that
     * also reads one source file, still counts for the calls it makes in code.
     */
    static String withoutLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            int end = SourceScan.endOfLiteralOrComment(source, i);
            char ch = source.charAt(i);
            if (end < 0 || ch == '/') {
                out.append(ch);
                continue;
            }
            int last = Math.min(end, source.length() - 1);
            int delimiter = source.startsWith("\"\"\"", i) ? 3 : 1;
            for (int k = i; k <= last; k++) {
                boolean edge = k < i + delimiter || k > last - delimiter;
                char c = source.charAt(k);
                out.append(edge || c == '\n' ? c : ' ');
            }
            i = last;
        }
        return out.toString();
    }

    /**
     * A Mockito stub or verification: {@code when(service.update(…))}, {@code verify(service).update(…)},
     * {@code given(…)}, {@code doReturn(x).when(service).update(…)}. The method named inside is
     * mocked, not exercised — D923 had exactly such a slice test — so these regions are blanked
     * before the names are collected.
     */
    private static final Pattern STUB_CALL = Pattern.compile(
        "\\b(?:when|given|verify|verifyNoMoreInteractions|inOrder|then)\\(");

    /**
     * A call on the CONTROLLER itself in a slice test ({@code controller.updateBed(id, request)}
     * with the service mocked) exercises no service method: its name is blanked too.
     */
    private static final Pattern CONTROLLER_CALL = Pattern.compile(
        "\\b(?:[abd-zABD-Z0-9_]|[cC](?!ontroller))*+[cC]ontroller\\w*+\\s*+\\.\\s*+(\\w++)\\s*+\\(");

    /**
     * A method DECLARATION in test code ({@code @Test void updatePermission() {}}): the
     * name is the test's, not a call to the service, and must not count as one.
     */
    private static final Pattern DECLARATION = Pattern.compile(
        "(?m)^\\s*+((?:[\\w<>\\[\\],.?]++\\s*+)++)\\([^;{)]*+\\)\\s*+(?:throws[^{;]*+)?+\\{");
    /** One word of a declaration head: its modifiers, its type and, last, its name. */
    private static final Pattern HEAD_WORD = Pattern.compile("[\\w<>\\[\\],.?]++");
    private static final Pattern NAME = Pattern.compile("\\w++");

    /**
     * The declared name's {start, end} in {@code source}: the last word of the head
     * (group 1), when it is a plain identifier preceded by at least one other word
     * (a type, {@code void}, a modifier); empty otherwise. A lone word indented by a
     * space and more whitespace ({@code if (x) {}, {@code for (…) {}) is blanked too,
     * as the earlier pattern did (its type class held a space): a keyword, never a
     * method name, so it changes nothing.
     */
    static int[] declaredName(Matcher declaration) {
        Matcher word = HEAD_WORD.matcher(declaration.group(1));
        int words = 0;
        int start = -1;
        int end = -1;
        while (word.find()) {
            words++;
            start = word.start();
            end = word.end();
        }
        String lead = declaration.group().substring(0, declaration.start(1) - declaration.start());
        boolean indentedLoneWord = words == 1 && lead.indexOf(' ') >= 0 && lead.indexOf(' ') < lead.length() - 1;
        if ((words < 2 && !indentedLoneWord) || !NAME.matcher(declaration.group(1).substring(start, end)).matches()) {
            return new int[0];
        }
        return new int[] {declaration.start(1) + start, declaration.start(1) + end};
    }

    static String withoutStubs(String source) {
        StringBuilder out = new StringBuilder(source);
        Matcher declaration = DECLARATION.matcher(source);
        while (declaration.find()) {
            int[] name = declaredName(declaration);
            if (name.length == 2) {
                for (int i = name[0]; i < name[1]; i++) {
                    out.setCharAt(i, ' ');
                }
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
            // verify(service).update(...), then(service).should().update(...): every method
            // in the chain after the closing paren is the stubbed one, not a call.
            int after = close + 1;
            for (int hop = 0; hop < 3; hop++) {
                while (after < source.length() && Character.isWhitespace(source.charAt(after))) {
                    after++;
                }
                if (after >= source.length() || source.charAt(after) != '.') {
                    break;
                }
                int nameEnd = after + 1;
                while (nameEnd < source.length() && Character.isLetterOrDigit(source.charAt(nameEnd))) {
                    nameEnd++;
                }
                for (int i = after; i < nameEnd; i++) {
                    out.setCharAt(i, ' ');
                }
                after = nameEnd;
                if (after < source.length() && source.charAt(after) == '(') {
                    after = SourceScan.closingParen(source, after) + 1;
                }
            }
        }
        Matcher controllerCall = CONTROLLER_CALL.matcher(source);
        while (controllerCall.find()) {
            for (int i = controllerCall.start(1); i < controllerCall.end(1); i++) {
                out.setCharAt(i, ' ');
            }
        }
        return out.toString();
    }

    /**
     * The names every test calls, read once, so each handler is a set lookup. Every
     * string literal and text block (the guards' fixtures), every comment and every
     * Mockito stub are left out: a sentence in a Javadoc, a `save(` inside a guard's
     * fixture string, or a
     * `when(service.updateFoo(…))` is not a test exercising the method.
     */
    private static Set<String> namesCalledByTests() throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(TESTS)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String calls = withoutStubs(withoutLiterals(
                    SourceScan.stripComments(Files.readString(p, StandardCharsets.UTF_8))));
                Matcher m = CALLED.matcher(calls);
                while (m.find()) {
                    names.add(m.group(1));
                }
                // The `service.method` pairs too: a generic name is covered only by its own
                // pair — by the field name, or by the service TYPE of the receiver the test
                // calls it on (`AdvanceDirectiveService service; … service.update(`). A type
                // the file merely names (a @Mock dependency of another service's test) does
                // not pair with the calls on other receivers.
                Matcher pair = SERVICE_CALL.matcher(calls);
                while (pair.find()) {
                    names.add(pair.group(1) + "." + pair.group(2));
                }
                Map<String, String> typeOf = new TreeMap<>();
                Matcher declared = TYPED_RECEIVER.matcher(calls);
                while (declared.find()) {
                    typeOf.put(declared.group(2), declared.group(1));
                }
                Matcher call = RECEIVER_CALL.matcher(calls);
                while (call.find()) {
                    String type = typeOf.get(call.group(1));
                    if (type != null) {
                        names.add(type + "." + call.group(2));
                    }
                }
            }
        }
        return names;
    }

    /** A receiver declared with a service type: {@code AdvanceDirectiveService service;}, {@code @Mock BarServiceImpl bar =}. */
    private static final Pattern TYPED_RECEIVER = Pattern.compile("\\b([A-Z]\\w*Service)(?:Impl)?\\s+(\\w+)\\s*[;=]");
    /** {@code receiver.method(} on any identifier. */
    private static final Pattern RECEIVER_CALL = Pattern.compile("\\b(\\w+)\\s*\\.\\s*(\\w+)\\s*\\(");

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
                // Keyed by the controller/-relative path: two controllers of one simple name
                // in different packages stay two controllers.
                String controller = MAIN.relativize(p).toString().replace('\\', '/').replace(".java", "");
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
            then(quuxService).should().updateQuux(id, request);
            then(quuxService).should(times(2)).archiveQuux(id);
            controller.updateBed(id, request);
            FooResponse saved = fooService.renameFoo(id, request);
            @Test
            void updateQux() {
                mockMvc.perform(put("/qux/1")).andExpect(status().isOk());
            }
            """;
        String kept = withoutStubs(test);
        assertThat(kept).contains("renameFoo(", "perform(")
            .doesNotContain("updateFoo(", "updateBar(", "updateBaz(", "updateQux(", "updateQuux(", "archiveQuux(",
                "updateBed(");
    }

    @Test
    @DisplayName("a method named inside a fixture literal is not a call; one called in code is, whatever the file's name")
    void fixtureLiteralsAreNotCalls() {
        String tq = "\"\"\"";
        // A source-scanning guard: its fixture spells the handler's service call.
        String guard = "String fixture = " + tq + "\n    advanceDirectiveService.update(id, request);\n    " + tq + ";\n"
            + "String one = \"wardService.renameWard(id)\";\n";
        // A behaviour test that also reads one production file still calls the service in code.
        String behaviour = """
            String src = Files.readString(Path.of("src/main/java/A.java"));
            service.pause(auth, refillId, null);
            """;
        String blanked = withoutLiterals(guard);
        assertThat(blanked).doesNotContain("update(", "renameWard(").contains(tq).contains("\n")
            .hasSameSizeAs(guard);
        assertThat(withoutLiterals(behaviour)).contains("service.pause(").doesNotContain("src/main");
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
