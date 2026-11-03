package com.example.hms.architecture;

import com.example.hms.enums.ClinicalPriority;
import com.example.hms.enums.LabOrderChannel;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.PatientGender;
import com.example.hms.enums.PaymentMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestBody;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1348 (round 30): {@code POST /medical-history/immunizations} accepted
 * any free-text status ("FAIT", "Donné") and stored it raw, outside the
 * vocabulary the series, overdue, reminder and export queries key on
 * ({@code status = 'COMPLETED'}), so the dose was invisible to all of them.
 *
 * <p>A request-body field named {@code status} is a vocabulary: it is an enum
 * (Jackson refuses any other value, by field), or a string that the code
 * checks against its enum with a field-named refusal before it is stored -
 * each such string listed here with where that check is - or a disposition
 * nothing reads but a person (listed with why). A new free-text status fails.
 * Stale entries fail.
 *
 * <p>QA D1373 (round 31, the same class; D1348 NOT_FIXED): {@code PATCH} and
 * {@code POST /patients} stored any sex ("HOMME", "X"), and the lab-range,
 * childbearing-potential and Né/Née readers fell back to "neither". Round 30
 * read only fields named {@code status}. The rule now covers every request
 * field whose name says it is one of a closed list - a status, a sex or
 * gender, a priority, a payment method, a channel, a direction. A String one
 * passes when it carries an edge {@code @Pattern} (the vocabulary's codes,
 * refused by name with the rest of the body: D1363 payment method, D1370
 * lab-order priority, status and channel, D1373 sex) or is listed with where
 * it is checked.
 */
@DisplayName("D1348/D1373: a closed-vocabulary request field is an enum or checked against one, never free text")
class FreeTextStatusRequestFieldGuardTest {

    private static final Path CONTROLLERS = Paths.get("src/main/java/com/example/hms/controller");

    /**
     * QA D1373: the request field names that say "one of a closed list" -
     * exact names, and the endings of a camel-case one ({@code relativeGender}).
     */
    private static final Set<String> VOCABULARY_NAMES = Set.of(
        "status", "gender", "sex", "priority", "paymentMethod", "method", "channel", "orderChannel", "direction");
    private static final List<String> VOCABULARY_ENDINGS = List.of("Gender", "Sex");

    /** "Class#field" of a String vocabulary checked in code (with where), or read by no rule (with why). */
    private static final Map<String, String> CHECKED_IN_CODE = Map.ofEntries(
        Map.entry("DoctorChartSectionDTO#status",
            "a free disposition per chart section (ACTIVE, RESOLVED, a narrative word): stored on"
                + " PatientChartSectionEntry and shown as written; no query or rule reads it"),
        Map.entry("TransitionLabOrderStatusRequestDTO#status",
            "LabOrderController#transitionLabOrderStatus: LabOrderStatus.valueOf, fieldErrors.status lab.order.unknownStatus"),
        Map.entry("LabInstrumentRequestDTO#status",
            "LabInstrumentMapper: LabInstrumentStatus.valueOf, refused when unknown"),
        Map.entry("NurseMedicationAdministrationRequestDTO#status",
            "NurseTaskServiceImpl#normalizeAdministrationStatus: fieldErrors.status nurse.mar.unsupportedStatus"),
        Map.entry("CrossmatchRequestDTO#method",
            "how the laboratory ran the crossmatch (immediate spin, AHG, electronic, a local name): stored on the"
                + " crossmatch and shown as written; no rule reads it"),
        Map.entry("EmpiCandidateQueryDTO#sex",
            "a search input, never stored: EmpiSimilarity#sexSimilarity scores it against the record and reads"
                + " any spelling"),
        Map.entry("FamilyHistoryRequestDTO#relativeGender",
            "the sex of a relative as the patient tells it, in a family-history narrative: stored and shown as"
                + " written; no rule reads it (the patient's own sex is PatientGender)"),
        Map.entry("UserMfaEnrollmentRequestDTO#channel",
            "the destination a one-time code is sent to (a phone number, an address), not a vocabulary; the"
                + " method is the MfaMethodType enum"));

    @Test
    @DisplayName("every closed-vocabulary request field is an enum, edge-checked, or listed with its check")
    void everyStatusIsAVocabulary() throws IOException {
        Set<String> freeText = new TreeSet<>();
        Set<Class<?>> seen = new LinkedHashSet<>();
        for (Class<?> body : requestBodies()) {
            collect(body, seen, freeText);
        }
        assertThat(seen).as("request classes read").hasSizeGreaterThan(300);
        Set<String> stale = new TreeSet<>(CHECKED_IN_CODE.keySet());
        stale.removeAll(freeText);
        freeText.removeAll(CHECKED_IN_CODE.keySet());
        assertThat(stale).as("entries that are no longer a String status (remove them)").isEmpty();
        assertThat(freeText)
            .as("a String vocabulary stored as sent is outside the codes its readers key on (QA D1348, D1373);"
                + " make it the enum, or check it at the edge with @Pattern(regexp = TheEnum.CODES)")
            .isEmpty();
    }

    private static void collect(Class<?> type, Set<Class<?>> seen, Set<String> freeText) {
        if (!type.getName().startsWith("com.example.hms.") || type.isEnum() || type.isInterface() || !seen.add(type)) {
            return;
        }
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (f.getType() == String.class && isVocabulary(f.getName())
                        && !f.isAnnotationPresent(jakarta.validation.constraints.Pattern.class)) {
                    freeText.add(c.getSimpleName() + "#" + f.getName());
                }
                collect(f.getType(), seen, freeText);
                if (f.getGenericType() instanceof ParameterizedType p) {
                    for (Type argument : p.getActualTypeArguments()) {
                        if (argument instanceof Class<?> element) {
                            collect(element, seen, freeText);
                        }
                    }
                }
            }
        }
    }

    static boolean isVocabulary(String name) {
        return VOCABULARY_NAMES.contains(name) || VOCABULARY_ENDINGS.stream().anyMatch(name::endsWith);
    }

    @Test
    @DisplayName("the edge check of a vocabulary lists exactly the enum's codes, in any case")
    void everyEdgeCheckListsTheEnumsCodes() {
        Map<Class<? extends Enum<?>>, String> checks = Map.of(
            PatientGender.class, PatientGender.CODES,
            PaymentMethod.class, PaymentMethod.CODES,
            LabOrderStatus.class, LabOrderStatus.CODES,
            LabOrderChannel.class, LabOrderChannel.CODES,
            ClinicalPriority.class, ClinicalPriority.CODES);
        checks.forEach((type, codes) -> {
            Pattern edge = Pattern.compile(codes, Pattern.CASE_INSENSITIVE);
            for (Enum<?> value : type.getEnumConstants()) {
                assertThat(edge.matcher(value.name()).matches()).as(type.getSimpleName() + "." + value).isTrue();
                assertThat(edge.matcher(value.name().toLowerCase(Locale.ROOT)).matches())
                    .as("any case: " + value).isTrue();
            }
            assertThat(edge.matcher("").matches()).as("blank is left to the required rule").isTrue();
            assertThat(edge.matcher("BOGUS").matches()).as(type.getSimpleName() + " refuses an unknown code").isFalse();
            if (type == PatientGender.class) {
                PatientGender.ALIASES.forEach((alias, code) -> {
                    assertThat(edge.matcher(alias).matches()).as("alias " + alias).isTrue();
                    assertThat(PatientGender.normalize(alias.toLowerCase(Locale.ROOT))).isEqualTo(code.name());
                });
            }
            assertThat(edge.matcher(" " + type.getEnumConstants()[0].name() + " ").matches())
                .as("surrounding spaces, as the readers trim them").isTrue();
            assertThat(codes.chars().filter(c -> c == '|').count())
                .as(type.getSimpleName() + ": one alternative per code")
                .isEqualTo(type.getEnumConstants().length - 1L + (type == PatientGender.class ? PatientGender.ALIASES.size() : 0));
        });
    }

    private static Set<Class<?>> requestBodies() throws IOException {
        Set<Class<?>> out = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(CONTROLLERS)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = Paths.get("src/main/java").relativize(f).toString().replace('\\', '/').replace(".java", "");
                try {
                    for (Method m : Class.forName(rel.replace('/', '.')).getDeclaredMethods()) {
                        addBodies(m, out);
                    }
                } catch (ClassNotFoundException e) {
                    // not a top-level class of that name
                }
            }
        }
        return out;
    }

    private static void addBodies(Method m, Set<Class<?>> out) {
        for (Parameter p : m.getParameters()) {
            if (!p.isAnnotationPresent(RequestBody.class)) {
                continue;
            }
            out.add(p.getType());
            if (p.getParameterizedType() instanceof ParameterizedType pt) {
                for (Type argument : pt.getActualTypeArguments()) {
                    if (argument instanceof Class<?> element) {
                        out.add(element);
                    }
                }
            }
        }
    }
}
