package com.example.hms.security.audit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps {@link PatientAccessAuditInterceptor}'s convention true as controllers
 * are added.
 *
 * <p>The interceptor finds the patient by convention — a {@code {patientId}}
 * path variable, or {@code {id}} under {@code /patients/**} — with
 * {@link PatientAccessAudited} as the override. An endpoint that matches
 * neither is not audited, and nothing about that is visible: the endpoint
 * works, the tests pass, and the patient's disclosure page simply never
 * mentions it. That is precisely how the gap this closes came to exist, so
 * the convention is enforced here rather than left to memory.
 *
 * <p><b>What this can and cannot prove.</b> It checks every GET whose route
 * mentions a patient. It cannot decide, from a URL, whether
 * {@code /lab-results/{id}} returns patient data — so passing this test is not
 * proof of complete coverage, only that nothing whose route says "patient"
 * fails the convention silently. Endpoints that serve patient data under a
 * route that never says so still need {@link PatientAccessAudited} and a
 * person to notice; that limit is real and is why this file does not claim
 * more.
 */
@DisplayName("Patient-access audit coverage")
class PatientAccessCoverageTest {

    private static final String CONTROLLER_PACKAGE = "com.example.hms.controller";

    private record Endpoint(String controller, String method, String route) {
        @Override
        public String toString() {
            return route + "  (" + controller + "#" + method + ")";
        }
    }

    @Test
    @DisplayName("every patient-scoped GET resolves to a patient, or says why it does not")
    void everyPatientScopedGetIsAttributable() {
        List<Endpoint> unattributable = new ArrayList<>();

        for (Class<?> controller : controllers()) {
            String prefix = classPrefix(controller);
            for (Method method : controller.getDeclaredMethods()) {
                GetMapping get = AnnotatedElementUtils.findMergedAnnotation(method, GetMapping.class);
                if (get == null) {
                    continue;
                }
                for (String route : routes(prefix, get)) {
                    if (mentionsAPatient(route) && !isAttributable(route, method)) {
                        unattributable.add(
                            new Endpoint(controller.getSimpleName(), method.getName(), route));
                    }
                }
            }
        }

        Set<String> sorted = new TreeSet<>(unattributable.stream().map(Endpoint::toString).toList());
        assertThat(sorted)
            .withFailMessage("""
                These GET endpoints are about a patient but the access auditor cannot tell \
                which one, so reads of them will never appear on that patient's disclosure \
                page:

                %s

                Fix by naming the path variable {patientId}, or by annotating the method \
                @PatientAccessAudited("<the variable that holds it>"). If the endpoint \
                genuinely should not be recorded, say so with \
                @PatientAccessAudited(skip = true) and a comment giving the reason.""",
                String.join("\n", sorted))
            .isEmpty();
    }

    /**
     * A route identifies <em>one</em> patient when a {@code patient} or
     * {@code patients} segment appears before a path variable.
     *
     * <p>The first attempt at this simply looked for "patient" anywhere in the
     * route, which flagged 77 endpoints and was wrong about most of them.
     * {@code /patient-education/resources/{id}} is a content library.
     * {@code /patients/search}, {@code /patients} and {@code /patient-tracker}
     * return many patients, and an audit row attributes an access to one
     * person — there is nothing to write for a list. Widening the rule to
     * catch those would only teach people to bulk-annotate their way past it.
     *
     * <p>{@code /me/**} is excluded: those are patients reading their own
     * records, which the interceptor skips by role because self-access is not
     * a disclosure.
     */
    private static boolean mentionsAPatient(String route) {
        if (route.startsWith("/me/")) {
            return false;
        }
        String[] segments = route.split("/");
        boolean afterPatientSegment = false;
        for (String segment : segments) {
            if (segment.startsWith("{") && afterPatientSegment) {
                return true;
            }
            if ("patient".equals(segment) || "patients".equals(segment)) {
                afterPatientSegment = true;
            }
        }
        return false;
    }

    private static boolean isAttributable(String route, Method method) {
        PatientAccessAudited annotation =
            AnnotatedElementUtils.findMergedAnnotation(method, PatientAccessAudited.class);
        if (annotation != null && (annotation.skip() || !annotation.value().isBlank())) {
            return true;
        }
        if (route.contains("{patientId}")) {
            return true;
        }
        return route.startsWith("/patients/") && route.contains("{id}");
    }

    private static List<Class<?>> controllers() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            String name = definition.getBeanClassName();
            if (name == null) {
                continue;
            }
            try {
                found.add(Class.forName(name));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Scanned a controller that will not load: " + name, e);
            }
        }
        assertThat(found)
            .withFailMessage("Found no controllers under %s — the scan is broken, not the code",
                CONTROLLER_PACKAGE)
            .isNotEmpty();
        return found;
    }

    private static String classPrefix(Class<?> controller) {
        RequestMapping mapping =
            AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        if (mapping == null || mapping.value().length == 0) {
            return "";
        }
        return normalise(mapping.value()[0]);
    }

    private static List<String> routes(String prefix, GetMapping get) {
        String[] paths = get.value().length > 0 ? get.value() : get.path();
        if (paths.length == 0) {
            return List.of(prefix.isEmpty() ? "/" : prefix);
        }
        List<String> routes = new ArrayList<>();
        for (String path : paths) {
            routes.add(prefix + normalise(path));
        }
        return routes;
    }

    private static String normalise(String path) {
        if (path.isBlank()) {
            return "";
        }
        return path.startsWith("/") ? path : "/" + path;
    }
}
