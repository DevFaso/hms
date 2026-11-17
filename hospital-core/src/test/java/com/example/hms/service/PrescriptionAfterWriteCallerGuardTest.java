package com.example.hms.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code PrescriptionService.getPrescriptionAfterWrite} is a public read that
 * deliberately skips the patient-ownership guard, and until this test the only
 * thing stopping a read path from calling it was a javadoc sentence.
 *
 * <p>That is the drift this repo has been bitten by before — hence the
 * {@code SchedulerLockCoverageTest} idiom of pinning the fact in a test rather
 * than in a comment. A future GET that reaches for the shorter-named method
 * would silently reopen exactly the leak the guard closed, with nothing failing.
 *
 * <p>Source-scanning, not reflection: what matters is which handler contains the
 * call, and that is a fact about the source, not about the loaded class. The
 * complement is asserted too — the by-id read must keep calling the guarded
 * {@code getPrescriptionById} — so relaxing the guard by swapping the call is a
 * failure and not merely an unpinned change.
 */
@DisplayName("Only the prescription write handlers may skip the ownership guard")
class PrescriptionAfterWriteCallerGuardTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "example", "hms");
    private static final Path CONTROLLER =
        MAIN.resolve(Path.of("controller", "PrescriptionController.java"));

    private static final String UNGUARDED = "getPrescriptionAfterWrite";
    private static final String GUARDED = "getPrescriptionById";

    /**
     * The write endpoints that may skip the guard: the two that admit roles the
     * by-id read does not. {@code POST /{id}/resolve-clarification} is
     * deliberately NOT here — it is ROLE_DOCTOR-only, so the guard is a no-op
     * for it and it stays on the guarded read.
     */
    private static final Set<String> ALLOWED_WRITE_PATHS = Set.of(
        "POST /{id}/pharmacist-verify",
        "POST /{id}/request-clarification");

    /** Any Spring handler mapping, with its verb and its path literal when it has one. */
    private static final Pattern MAPPING = Pattern.compile(
        "@(Get|Post|Put|Patch|Delete|Request)Mapping\\s*(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\")?");

    @Test
    @DisplayName("no file but the controller calls it, and only from the three write handlers")
    void onlyTheWriteHandlersCallTheUnguardedRead() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!source.contains(UNGUARDED + "(")) {
                    continue;
                }
                // The declaration and the @Override are not calls — but the
                // file is not skipped wholesale: a second mention inside
                // PrescriptionServiceImpl would be a delegation from some new
                // read, which is the very drift this test exists to catch.
                if (file.endsWith(Path.of("service", "PrescriptionService.java"))
                    || file.endsWith(Path.of("service", "PrescriptionServiceImpl.java"))) {
                    int mentions = source.split(UNGUARDED + "\\(", -1).length - 1;
                    if (mentions > 1) {
                        offenders.add(file + " mentions " + UNGUARDED + " " + mentions
                            + " times; only its own declaration may, so one of them is a"
                            + " delegation that skips the ownership guard.");
                    }
                    continue;
                }
                if (!file.endsWith(CONTROLLER)) {
                    offenders.add(file + " calls " + UNGUARDED
                        + "; only the prescription write handlers may.");
                }
            }
        }
        assertThat(offenders)
            .as("%s skips the patient-ownership guard; a read that calls it reopens the leak",
                UNGUARDED)
            .isEmpty();

        assertThat(handlerPathsCalling(UNGUARDED))
            .as("the unguarded read belongs to the write handlers and nowhere else")
            .isEqualTo(new TreeSet<>(ALLOWED_WRITE_PATHS));
    }

    @Test
    @DisplayName("the by-id read still calls the guarded method")
    void theByIdReadStaysGuarded() throws IOException {
        assertThat(handlerPathsCalling(GUARDED))
            .as("GET /prescriptions/{id} must go through the ownership guard")
            .contains("GET /{id}");
    }

    /**
     * The mapping path of every handler in the controller whose body mentions
     * {@code call}. Each mapping annotation opens a handler; the text up to the
     * next mapping annotation is its body.
     */
    private Set<String> handlerPathsCalling(String call) throws IOException {
        String source = withoutComments(Files.readString(CONTROLLER, StandardCharsets.UTF_8));
        List<Integer> starts = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        Matcher mapping = MAPPING.matcher(source);
        while (mapping.find()) {
            starts.add(mapping.start());
            // Keyed by verb as well as path: GET, PUT and DELETE all map
            // "/{id}" here, and collapsing them would let one handler's call
            // satisfy an assertion about another.
            paths.add(mapping.group(1).toUpperCase(java.util.Locale.ROOT)
                + " " + (mapping.group(2) == null ? "" : mapping.group(2)));
        }
        assertThat(starts).as("the controller must still declare handlers").isNotEmpty();

        Set<String> calling = new TreeSet<>();
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : source.length();
            if (source.substring(starts.get(i), end).contains(call + "(")) {
                calling.add(paths.get(i));
            }
        }
        // The first match is the class-level @RequestMapping, so the region
        // before it is imports and class javadoc; everything from there to the
        // first method mapping is field and constructor territory. A call in
        // either belongs to no handler, and test 1's exact-equality check would
        // report it as a phantom path rather than saying what it is.
        int firstHandler = starts.size() > 1 ? starts.get(1) : source.length();
        assertThat(source.substring(0, firstHandler))
            .as("%s must not be called from a field initialiser or the constructor", call)
            .doesNotContain(call + "(");
        return calling;
    }

    @Test
    @DisplayName("the clinical-reader set is exactly the endpoint's non-patient roles")
    void theRoleSetMirrorsTheAnnotation() throws IOException {
        String source = withoutComments(Files.readString(CONTROLLER, StandardCharsets.UTF_8));

        int handler = source.indexOf("@GetMapping(\"/{id}\")");
        assertThat(handler).as("GET /prescriptions/{id} must still exist").isNotNegative();

        // Only as far as the handler's own signature: the annotation may be a
        // concatenation of literals (it is, since #737 added a sixth role), so
        // a regex demanding one quoted string would miss it and silently match
        // the NEXT @PreAuthorize in the file — a different endpoint.
        int signature = source.indexOf("public ResponseEntity", handler);
        assertThat(signature).as("the by-id handler must still have a body").isGreaterThan(handler);
        String annotation = source.substring(handler, signature);
        assertThat(annotation).as("the by-id read must still be annotated").contains("@PreAuthorize");

        Set<String> admitted = new TreeSet<>();
        Matcher role = Pattern.compile("'(ROLE_[A-Z_]+)'").matcher(annotation);
        while (role.find()) {
            admitted.add(role.group(1));
        }
        assertThat(admitted).as("the annotation must still admit the patient")
            .contains("ROLE_PATIENT");
        admitted.remove("ROLE_PATIENT");

        assertThat(new TreeSet<>(PrescriptionReaderRoles.CLINICAL_READER_ROLES))
            .as("a role the annotation does not admit reaches this handler only through "
                + "ROLE_PATIENT, so exempting it would let it read a stranger's prescription "
                + "on the strength of the patient role that let it in; a role the annotation "
                + "admits but the set omits would be refused its own colleagues' orders")
            .isEqualTo(admitted);
    }

    /**
     * Comments out, so that naming the method in prose — as
     * {@code PrescriptionReaderRoles} does, explaining why the exemption lives
     * on the write path — is not read as calling it.
     */
    private static String withoutComments(String source) {
        return source
            .replaceAll("(?s)/\\*.*?\\*/", " ")
            // Not "//.*": a "https://..." literal would swallow the rest of its
            // line, and a call after it on that line would go unseen.
            .replaceAll("(?m)(^|[^:\"])//.*$", "$1 ");
    }
}
