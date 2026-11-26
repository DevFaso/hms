package com.example.hms.architecture;

import com.example.hms.controller.LabResultController;
import com.example.hms.controller.MicroCultureController;
import com.example.hms.controller.PatientLabResultController;
import com.example.hms.controller.PatientMicroCultureController;
import com.example.hms.controller.TransfusionController;
import com.example.hms.testsupport.SourceScan;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.EvaluationContext;
import org.springframework.security.access.expression.ExpressionUtils;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.io.IOException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3902 guard: a doctor and a nurse rewrote a FINAL antibiogram, because the culture endpoints
 * admitted DOCTOR, NURSE and MIDWIFE "to mirror POST /lab-results". A laboratory result is the
 * laboratory's: no clinical role may write a validated (final, released) result.
 *
 * <p>Every write endpoint of the laboratory's result controllers is listed below with what it is:
 * <ul>
 *   <li>{@link Kind#LAB_ONLY}: the endpoint writes a result (or validates one). Its real
 *       {@code @PreAuthorize}, evaluated, refuses each clinical role on its own, and admits a
 *       laboratory role;</li>
 *   <li>{@link Kind#UNRELEASED_ONLY}: bedside entry of a point-of-care result (POST/PUT
 *       /lab-results). A clinical role passes the gate, so the service must refuse a released
 *       result before writing ({@code requireNotReleased}); a correction of a released result is a
 *       new row the laboratory releases;</li>
 *   <li>{@link Kind#NOT_A_RESULT}: a clinical act around a result that does not change it (a
 *       request, a signature, an acknowledgement, a read-back, a bedside administration).</li>
 * </ul>
 * A new write endpoint fails this test until someone says which it is. The culture service's own
 * check of who may correct a final report ({@code CORRECTION_ROLES}) is held to the same rule.
 */
@DisplayName("No clinical role writes a finalised laboratory result")
class LabResultEntryEndpointsGuardTest {

    private enum Kind { LAB_ONLY, UNRELEASED_ONLY, NOT_A_RESULT }

    private static final List<String> CLINICAL_ROLES =
        List.of("ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_SURGEON", "ROLE_PHARMACIST");
    private static final List<String> LAB_ROLES =
        List.of("ROLE_LAB_SCIENTIST", "ROLE_LAB_TECHNICIAN", "ROLE_LAB_MANAGER", "ROLE_LAB_DIRECTOR");

    private static final List<Class<?>> RESULT_CONTROLLERS = List.of(
        MicroCultureController.class, PatientMicroCultureController.class,
        LabResultController.class, PatientLabResultController.class,
        TransfusionController.class);

    private static final Map<String, Kind> ENDPOINTS = new TreeMap<>(Map.ofEntries(
        // Microbiology: the report, its organisms and its antibiogram are the laboratory's.
        Map.entry("MicroCultureController#createCulture", Kind.LAB_ONLY),
        Map.entry("MicroCultureController#updateCulture", Kind.LAB_ONLY),
        Map.entry("MicroCultureController#finalizeCulture", Kind.LAB_ONLY),
        Map.entry("MicroCultureController#addIsolate", Kind.LAB_ONLY),
        Map.entry("MicroCultureController#updateIsolate", Kind.LAB_ONLY),
        Map.entry("MicroCultureController#deleteIsolate", Kind.LAB_ONLY),
        Map.entry("MicroCultureController#addSusceptibility", Kind.LAB_ONLY),
        Map.entry("MicroCultureController#deleteSusceptibility", Kind.LAB_ONLY),
        // General lab results.
        Map.entry("LabResultController#createLabResult", Kind.UNRELEASED_ONLY),
        Map.entry("LabResultController#updateLabResult", Kind.UNRELEASED_ONLY),
        Map.entry("LabResultController#releaseLabResult", Kind.LAB_ONLY),
        Map.entry("LabResultController#deleteLabResult", Kind.LAB_ONLY),
        Map.entry("LabResultController#signLabResult", Kind.NOT_A_RESULT),
        Map.entry("LabResultController#acknowledgeLabResult", Kind.NOT_A_RESULT),
        Map.entry("LabResultController#readBackCriticalValue", Kind.NOT_A_RESULT),
        Map.entry("LabResultController#runCriticalEscalationSweep", Kind.NOT_A_RESULT),
        // Blood bank: grouping, antibody work-up, crossmatch and issue are the laboratory's.
        Map.entry("TransfusionController#recordBloodGroup", Kind.LAB_ONLY),
        Map.entry("TransfusionController#recordAntibodyWorkup", Kind.LAB_ONLY),
        Map.entry("TransfusionController#receiveUnit", Kind.LAB_ONLY),
        Map.entry("TransfusionController#discardUnit", Kind.LAB_ONLY),
        Map.entry("TransfusionController#recordCrossmatch", Kind.LAB_ONLY),
        Map.entry("TransfusionController#issueUnit", Kind.LAB_ONLY),
        Map.entry("TransfusionController#emergencyRelease", Kind.LAB_ONLY),
        Map.entry("TransfusionController#createRequest", Kind.NOT_A_RESULT),
        Map.entry("TransfusionController#cancelRequest", Kind.NOT_A_RESULT),
        Map.entry("TransfusionController#startAdministration", Kind.NOT_A_RESULT),
        Map.entry("TransfusionController#completeAdministration", Kind.NOT_A_RESULT),
        Map.entry("TransfusionController#stopAdministration", Kind.NOT_A_RESULT),
        Map.entry("TransfusionController#recordReaction", Kind.NOT_A_RESULT),
        // V380: the O positive authority rule writes a decision's signature, review and follow-up, and a setting.
        Map.entry("TransfusionController#countersignPositiveRelease", Kind.NOT_A_RESULT),
        Map.entry("TransfusionController#reviewPositiveRelease", Kind.NOT_A_RESULT),
        Map.entry("TransfusionController#recordPositiveFollowUp", Kind.NOT_A_RESULT),
        Map.entry("TransfusionController#setPositiveProtocol", Kind.NOT_A_RESULT)));

    private final DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static List<Method> writeEndpoints() {
        List<Method> found = new ArrayList<>();
        for (Class<?> controller : RESULT_CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, PostMapping.class)
                    || AnnotatedElementUtils.hasAnnotation(method, PutMapping.class)
                    || AnnotatedElementUtils.hasAnnotation(method, PatchMapping.class)
                    || AnnotatedElementUtils.hasAnnotation(method, DeleteMapping.class)) {
                    found.add(method);
                }
            }
        }
        return found;
    }

    private static String keyOf(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }

    /** The gate Spring applies: the method's own @PreAuthorize, else the class's. */
    private static String gateOf(Method method) {
        PreAuthorize own = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        if (own != null) {
            return own.value();
        }
        PreAuthorize onClass = AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), PreAuthorize.class);
        return onClass != null ? onClass.value() : null;
    }

    private boolean admits(Method method, String role) {
        Authentication caller = new UsernamePasswordAuthenticationToken("caller", "n/a",
            List.of(new SimpleGrantedAuthority(role)));
        EvaluationContext context = handler.createEvaluationContext(() -> caller, invocationOf(method));
        return ExpressionUtils.evaluateAsBoolean(handler.getExpressionParser().parseExpression(gateOf(method)), context);
    }

    @Test
    @DisplayName("every write endpoint of the laboratory's result controllers is classified")
    void everyWriteEndpointIsClassified() {
        List<String> endpoints = writeEndpoints().stream().map(LabResultEntryEndpointsGuardTest::keyOf).sorted().toList();

        assertThat(endpoints)
            .as("say for each new write endpoint whether it writes a result (LAB_ONLY) — QA D3902")
            .containsExactlyInAnyOrderElementsOf(ENDPOINTS.keySet());
    }

    @Test
    @DisplayName("a result-writing endpoint refuses every clinical role and admits the laboratory")
    void resultWritingEndpointsAreTheLaboratorys() {
        List<String> clinicalAdmitted = new ArrayList<>();
        List<String> noLabRole = new ArrayList<>();
        for (Method method : writeEndpoints()) {
            if (ENDPOINTS.get(keyOf(method)) != Kind.LAB_ONLY) {
                continue;
            }
            assertThat(gateOf(method)).as(keyOf(method) + " has a @PreAuthorize").isNotBlank();
            for (String role : CLINICAL_ROLES) {
                if (admits(method, role)) {
                    clinicalAdmitted.add(keyOf(method) + " admits " + role);
                }
            }
            if (LAB_ROLES.stream().noneMatch(role -> admits(method, role))) {
                noLabRole.add(keyOf(method));
            }
        }
        assertThat(clinicalAdmitted).as("QA D3902: a clinical role writes a laboratory result").isEmpty();
        assertThat(noLabRole).as("a result endpoint no laboratory role can reach").isEmpty();
    }

    @Test
    @DisplayName("a clinician who may enter a bedside result can never change a released one")
    void bedsideEntryStopsAtRelease() throws IOException {
        String service = SourceScan.stripComments(Files.readString(
            Paths.get("src/main/java/com/example/hms/service/LabResultServiceImpl.java"), StandardCharsets.UTF_8));
        String update = SourceScan.methodBody(service, "updateLabResult");

        assertThat(update).as("updateLabResult refuses a released result").contains("requireNotReleased(labResult)");
        assertThat(update.indexOf("requireNotReleased(labResult)"))
            .as("...before it writes anything")
            .isLessThan(update.indexOf(".set"));
    }

    @Test
    @DisplayName("a final culture report is corrected by the laboratory's validating tier only")
    void finalCultureCorrectionsAreTheLaboratorysValidatingTier() throws Exception {
        Field field = Class.forName("com.example.hms.service.impl.MicroCultureServiceImpl")
            .getDeclaredField("CORRECTION_ROLES");
        AccessibleObject.setAccessible(new AccessibleObject[] {field}, true);
        List<String> roles = Arrays.stream((String[]) field.get(null))
            .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role).toList();

        assertThat(roles).isNotEmpty().doesNotContainAnyElementsOf(CLINICAL_ROLES)
            .doesNotContain("ROLE_LAB_TECHNICIAN");

        Path source = Paths.get("src/main/java/com/example/hms/service/impl/MicroCultureServiceImpl.java");
        String guard = SourceScan.methodBody(
            SourceScan.stripComments(Files.readString(source, StandardCharsets.UTF_8)), "applyCorrectionGuard");
        assertThat(guard).as("the correction guard checks the caller's own role")
            .contains("holdsOwnRoleQuietly").contains("CORRECTION_ROLES");
        assertThat(guard.indexOf("CORRECTION_ROLES"))
            .as("...before the correction row is written")
            .isLessThan(guard.indexOf("correctionRepository.save"));
    }

    private static MethodInvocation invocationOf(Method method) {
        return new MethodInvocation() {
            @Override
            public Method getMethod() {
                return method;
            }

            @Override
            public Object[] getArguments() {
                return new Object[method.getParameterCount()];
            }

            @Override
            public Object proceed() {
                return null;
            }

            @Override
            public Object getThis() {
                return null;
            }

            @Override
            public AccessibleObject getStaticPart() {
                return method;
            }
        };
    }
}
