package com.example.hms.architecture;

import com.example.hms.validation.MedicationRoute;
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
 * QA D1359 (round 30), class "a request field that names an administration
 * route accepts any text": {@code POST /api/prescriptions} stored "BOGUS" and
 * "PAR LA BOUCHE" as the order's route, and the MAR five-rights route check
 * reads that field. Every request-body field named {@code route} (found from
 * every controller's {@code @RequestBody}, nested types included, so a new
 * request is held the day it is added) carries {@link MedicationRoute}, or is
 * listed below with why it is another vocabulary. Stale entries fail.
 */
@DisplayName("D1359: every request field naming an administration route is validated against the route vocabulary")
class MedicationRouteFieldGuardTest {

    private static final String PACKAGE = "com.example.hms.";

    private static final Path CONTROLLERS = Paths.get("src/main/java/com/example/hms/controller");

    /** "class (below com.example.hms).route" -> why it is not the medication-order route vocabulary. */
    private static final Map<String, String> OTHER_VOCABULARY = Map.of(
        "payload.dto.NursingIntakeRequestDTO$MedicationReconciliationEntry.route",
        "a home medication as the patient reports it at nursing intake (eye drops, patches, any route): a history, "
            + "not an order the MAR checks",
        "payload.dto.discharge.MedicationReconciliationDTO.route",
        "a home medication as reconciled at discharge, as the patient reports it: a history, not an order the MAR "
            + "checks",
        "payload.dto.medicalhistory.ImmunizationRequestDTO.route",
        "a vaccine route (ID, IN are not medication-order routes); owned by fix/r30-request-validation (D1348 file)",
        "payload.dto.medication.MedicationCatalogItemRequestDTO.route",
        "the catalogue's default route for a product (ophthalmic, vaginal, transdermal exist); never compared at the "
            + "bedside, the order's own route is");

    @Test
    void everyRouteFieldIsValidatedOrListed() throws IOException {
        Set<String> unvalidated = new TreeSet<>();
        Set<String> found = new TreeSet<>();
        Set<Class<?>> seen = new LinkedHashSet<>();
        for (Class<?> body : requestBodies()) {
            collect(body, seen, found, unvalidated);
        }
        assertThat(found).as("route fields found in request bodies").contains("payload.dto.PrescriptionRequestDTO.route");
        unvalidated.removeAll(OTHER_VOCABULARY.keySet());
        assertThat(unvalidated)
            .as("a request field naming a route that accepts any text (QA D1359): add @MedicationRoute, "
                + "or list it here with why it is another vocabulary")
            .isEmpty();
        assertThat(found).as("stale entries (the field no longer exists)").containsAll(OTHER_VOCABULARY.keySet());
    }

    private static void collect(Class<?> type, Set<Class<?>> seen, Set<String> found, Set<String> unvalidated) {
        if (!type.getName().startsWith(PACKAGE) || type.isEnum() || type.isInterface() || !seen.add(type)) {
            return;
        }
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (f.getType() == String.class && "route".equalsIgnoreCase(f.getName())) {
                    String key = c.getName().substring(PACKAGE.length()) + "." + f.getName();
                    found.add(key);
                    if (!f.isAnnotationPresent(MedicationRoute.class)) {
                        unvalidated.add(key);
                    }
                }
                collect(f.getType(), seen, found, unvalidated);
                if (f.getGenericType() instanceof ParameterizedType p) {
                    for (Type argument : p.getActualTypeArguments()) {
                        if (argument instanceof Class<?> element) {
                            collect(element, seen, found, unvalidated);
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
                Class<?> controller;
                try {
                    controller = Class.forName(rel.replace('/', '.'));
                } catch (ClassNotFoundException e) {
                    continue;
                }
                for (Method m : controller.getDeclaredMethods()) {
                    for (Parameter p : m.getParameters()) {
                        if (p.isAnnotationPresent(RequestBody.class)) {
                            out.add(p.getType());
                        }
                    }
                }
            }
        }
        return out;
    }
}
