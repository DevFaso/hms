package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import com.example.hms.validation.ReportedOn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA round 29 (the D1245 / D1299 / D1312 class, NOT_FIXED three rounds): the
 * source rules that keep the behavioural sweep
 * ({@code controller/WriteEndpointRequiredFieldSweepIT}) honest where a call
 * cannot reach: a refusal after a record is found, a query parameter, a
 * message text.
 *
 * <ul>
 *   <li>A 400 with an empty body ({@code ResponseEntity.badRequest().build()})
 *       tells the form nothing: every refusal names its field.</li>
 *   <li>A cross-field rule ({@code @AssertTrue} on a request getter) names a
 *       request field with {@link ReportedOn}: its own getter name was the
 *       {@code fieldErrors} key (D1317 {@code referenceGivenForNonCashPayment}).</li>
 *   <li>A refusal whose key says a value is required is a field refusal
 *       ({@code FieldBusinessException}), unless the "value" is a context, a
 *       body as a whole, a path id or a state (listed by category or by key).</li>
 *   <li>No message shows a raw camelCase property ("(patientId)", D1319).</li>
 * </ul>
 */
@DisplayName("R29: every refusal names its field, and no message shows a raw property")
class R29FieldNamedRefusalGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Path PAYLOAD = MAIN.resolve("payload");
    private static final Path BUNDLES = Paths.get("src/main/resources/i18n");

    /** Empty-body 400s that are a protocol's answer, not a form's. */
    private static final Set<String> EMPTY_400_ALLOWED = Set.of(
        "cdshooks/CdsHooksController.java" // CDS Hooks 1.0: a hook the service does not advertise is refused bare
    );

    private static final Pattern EMPTY_400 = Pattern.compile("badRequest\\(\\)\\s*+\\.build\\(\\)");

    private static final Pattern REQUIRED_KEY_THROWN = Pattern.compile(
        "new (?:com\\.example\\.hms\\.exception\\.)?(?:BusinessException|BusinessRuleException)\\(\\s*+"
            // the key itself, or the key resolved first (message(...), MessageUtil.resolve(...), getMessage(...))
            + "(?:(?:\\w++\\.)*+(?:message|resolve|resolveOrRaw|getMessage|getLocalizedMessage)\\(\\s*+)?\"([\\w.]++)\"");
    private static final Pattern REQUIRED_SHAPE = Pattern.compile("(?i)required|missing");
    /** Required-shaped keys that are not a request field: a context, a whole body, a path id, the caller's role. */
    private static final Pattern NOT_A_FIELD_CATEGORY = Pattern.compile(
        "(?i)hospital|context|payload|bodyrequired|request(?:\\.)?required|role|requester|assignment|idrequired"
            + "|notrequired|patientrequired|userrequired|filterrequired|scope|\\.missing");
    /** Required-shaped keys outside those categories, each with why it is not a field. Shrinks only. */
    private static final Map<String, String> NOT_A_FIELD = Map.ofEntries(
        Map.entry("lab.definition.rangesRequired", "state: a definition goes live only with a grading basis"),
        Map.entry("lab.definition.rangesRequiredForReview", "state: a definition goes to review only with a grading basis"),
        Map.entry("nurse.mar.overrideRequired", "state of the dose's bedside checks"),
        Map.entry("nurse.mar.pharmacistVerificationRequired", "state: the order awaits the pharmacist's verification"),
        Map.entry("nurse.vitals.required", "a null body"),
        Map.entry("pharmacy.claim.export.statusRequired", "an export query parameter, named by the export screen"),
        Map.entry("audit.eventType.required", "a query parameter of the audit search"),
        Map.entry("lab.result.compareMissingData", "state: a comparison needs two results with values"),
        Map.entry("prescription.safeguard.COSIGN_REQUIRED.status", "state: the controlled order awaits its co-signature"),
        Map.entry("prescription.safeguard.COSIGN_REQUIRED.dispense", "state: the controlled order awaits its co-signature"),
        Map.entry("staff.department.required", "state of the staff record: availability needs the staff's department"),
        Map.entry("encounter.note.author.required", "the caller's staff identity, not a request field"),
        Map.entry("auth.lab.required", "the caller's role (lab scientist or hospital admin)"),
        Map.entry("auth.lab.staff.required", "the caller's role (lab staff or hospital admin)"));

    /** Units, clinical scores and external-system terms that are written in camel case on purpose. */
    private static final Set<String> CAMEL_CASE_WORDS = Set.of(
        "mmHg", "mL", "dL", "qSOFA", "sBP", "eMAR", "dataElement", "categoryOptionCombo");
    private static final Pattern CAMEL_CASE = Pattern.compile("\\b[a-z]++[A-Z][a-zA-Z]*+\\b");

    @Test
    @DisplayName("no handler answers a 400 with an empty body")
    void noEmptyBadRequest() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path f : javaFiles(MAIN)) {
            String rel = MAIN.relativize(f).toString().replace('\\', '/');
            String source = Files.readString(f, StandardCharsets.UTF_8);
            Matcher m = EMPTY_400.matcher(source);
            while (m.find()) {
                if (!EMPTY_400_ALLOWED.contains(rel)) {
                    found.add(rel + ":" + lineOf(source, m.start()));
                }
            }
        }
        assertThat(found)
            .as("a 400 with no message and no field: throw a FieldBusinessException naming the parameter (QA round 29)"
                + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("every cross-field rule on a request body is reported on one of its fields")
    void everyAssertTrueIsReportedOnARequestField() throws Exception {
        List<String> found = new ArrayList<>();
        int rules = 0;
        for (Path f : javaFiles(PAYLOAD)) {
            String source = Files.readString(f, StandardCharsets.UTF_8);
            if (!source.contains("AssertTrue")) {
                continue;
            }
            Class<?> type = classOf(f);
            for (Method method : type.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(jakarta.validation.constraints.AssertTrue.class)) {
                    continue;
                }
                rules++;
                ReportedOn reportedOn = method.getAnnotation(ReportedOn.class);
                if (reportedOn == null || !hasField(type, reportedOn.value())) {
                    found.add(type.getSimpleName() + "#" + method.getName()
                        + (reportedOn == null ? " (no @ReportedOn)" : " (no field " + reportedOn.value() + ")"));
                }
            }
        }
        assertThat(rules).as("@AssertTrue rules read").isGreaterThan(15);
        assertThat(found)
            .as("its fieldErrors key is the getter's name, which no form control carries: add @ReportedOn naming the"
                + " field the form marks (QA D1317)" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("a refusal keyed 'required' names its field, unless it is a context, a body, a path id or a state")
    void everyRequiredRefusalNamesItsField() throws IOException {
        List<String> found = new ArrayList<>();
        Set<String> listedSeen = new TreeSet<>();
        for (Path f : javaFiles(MAIN)) {
            String source = Files.readString(f, StandardCharsets.UTF_8);
            Matcher m = REQUIRED_KEY_THROWN.matcher(source);
            while (m.find()) {
                String key = m.group(1);
                if (!REQUIRED_SHAPE.matcher(key).find() || NOT_A_FIELD_CATEGORY.matcher(key).find()) {
                    continue;
                }
                if (NOT_A_FIELD.containsKey(key)) {
                    listedSeen.add(key);
                } else {
                    found.add(key + " (" + MAIN.relativize(f) + ":" + lineOf(source, m.start()) + ")");
                }
            }
        }
        assertThat(found)
            .as("a required value refused with no field, so the form marks nothing: FieldBusinessException(field, key)"
                + " (QA round 29)" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(listedSeen).as("NOT_A_FIELD entries no longer thrown: delete them")
            .containsExactlyInAnyOrderElementsOf(NOT_A_FIELD.keySet());
    }

    @Test
    @DisplayName("no message in any bundle shows a raw camelCase property")
    void noMessageShowsARawProperty() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path bundle : propertyFiles()) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(bundle)) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (String key : props.stringPropertyNames()) {
                if (key.startsWith("field.label.")) {
                    continue;
                }
                Matcher m = CAMEL_CASE.matcher(props.getProperty(key));
                while (m.find()) {
                    if (!CAMEL_CASE_WORDS.contains(m.group())) {
                        found.add(bundle.getFileName() + ": " + key + " -> " + m.group());
                    }
                }
            }
        }
        assertThat(found)
            .as("a message that shows the reader an internal property name, e.g. \"(patientId)\" (QA D1319): say it in"
                + " words" + SourceScan.MANDATE)
            .isEmpty();
    }

    private static boolean hasField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Class<?> classOf(Path file) throws ClassNotFoundException {
        String rel = MAIN.relativize(file).toString().replace('\\', '/').replace(".java", "").replace('/', '.');
        return Class.forName("com.example.hms." + rel);
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static List<Path> propertyFiles() throws IOException {
        try (Stream<Path> files = Files.list(BUNDLES)) {
            return files.filter(p -> p.toString().endsWith(".properties")).sorted().toList();
        }
    }

    private static int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
