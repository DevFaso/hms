package com.example.hms.architecture;

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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 */
@DisplayName("D1348: a request status field is an enum or checked against one, never free text")
class FreeTextStatusRequestFieldGuardTest {

    private static final Path CONTROLLERS = Paths.get("src/main/java/com/example/hms/controller");

    /** "Class#field" of a String status checked in code (with where), or read by no rule (with why). */
    private static final Map<String, String> CHECKED_IN_CODE = Map.of(
        "DoctorChartSectionDTO#status",
        "a free disposition per chart section (ACTIVE, RESOLVED, a narrative word): stored on"
            + " PatientChartSectionEntry and shown as written; no query or rule reads it",
        "TransitionLabOrderStatusRequestDTO#status",
        "LabOrderController#transitionLabOrderStatus: LabOrderStatus.valueOf, fieldErrors.status lab.order.unknownStatus",
        "LabInstrumentRequestDTO#status",
        "LabInstrumentMapper: LabInstrumentStatus.valueOf, refused when unknown",
        "LabOrderRequestDTO#status",
        "LabOrderServiceImpl#applyRequestedStatus / LabOrderMapper: LabOrderStatus.valueOf",
        "NurseMedicationAdministrationRequestDTO#status",
        "NurseTaskServiceImpl#normalizeAdministrationStatus: fieldErrors.status nurse.mar.unsupportedStatus",
        "SuperAdminLabOrderCreateRequestDTO#status",
        "copied into LabOrderRequestDTO#status (SuperAdminLabOrderServiceImpl#buildLabOrderRequest)");

    @Test
    @DisplayName("every request field named status is an enum or listed with its check")
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
            .as("a String status stored as sent is outside the vocabulary its readers key on (QA D1348);"
                + " make it the enum")
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
                if (f.getType() == String.class && f.getName().equals("status")) {
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
