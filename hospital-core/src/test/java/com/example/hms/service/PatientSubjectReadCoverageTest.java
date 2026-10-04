package com.example.hms.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A ratchet over the reads a patient can reach by id: every GET handler whose
 * {@code @PreAuthorize} (its own, or its class's) admits {@code ROLE_PATIENT}
 * and takes a path variable is either registered with
 * {@link PatientSubjectReadGuard} — listed in
 * {@code PatientSubjectReaderRolesMirrorTest}, which also pins its role set —
 * or named below with the reason a patient is already bound to their own
 * record there.
 *
 * <p>Thirteen such reads served any id they were given, and nothing noticed:
 * admitting {@code ROLE_PATIENT} and taking an id is exactly the shape that
 * leaks, and it looks the same whether the service checks ownership or not.
 * This makes adding one a decision rather than an accident. It does not prove
 * the allowlisted reasons stay true; it makes each one written down and
 * reviewable.
 *
 * <p>Modelled on {@code SchedulerLockCoverageTest}: controllers are found from
 * class metadata on the classpath, not from a Spring context, so a
 * conditional controller is not silently skipped; annotations are resolved
 * with {@link AnnotatedElementUtils}, so a composed or meta-annotated mapping
 * or {@code @PreAuthorize} is seen as Spring sees it.
 */
@DisplayName("Patient-subject read coverage")
class PatientSubjectReadCoverageTest {

    private static final Pattern ADMITS_PATIENT = Pattern.compile("'(ROLE_)?PATIENT'");

    /** Reads that admit a patient by id and already hold them to their own record, and where. */
    private static final Map<String, String> OWNED_ELSEWHERE = Map.ofEntries(
        entry("EncounterController#getById", "EncounterServiceImpl.requireEncounterReadable (#746/#754)"),
        entry("EncounterController#getAfterVisitSummary", "EncounterServiceImpl.requireEncounterReadable (#746/#754)"),
        entry("PrescriptionController#getById", "PrescriptionServiceImpl ownership via PrescriptionReaderRoles"),
        entry("BirthPlanController#getBirthPlanById", "BirthPlanServiceImpl.checkBirthPlanAccess: own row only"),
        entry("BirthPlanController#getBirthPlansByPatientId", "BirthPlanServiceImpl: patient id must be the caller's row"),
        entry("BirthPlanController#getActiveBirthPlan", "BirthPlanServiceImpl: patient id must be the caller's row"),
        entry("HighRiskPregnancyCarePlanController#getPlan", "HighRiskPregnancyCarePlanServiceImpl.assertReadAccess(plan)"),
        entry("HighRiskPregnancyCarePlanController#getPlansForPatient", "HighRiskPregnancyCarePlanServiceImpl.assertReadAccess(patient)"),
        entry("HighRiskPregnancyCarePlanController#getActivePlan", "HighRiskPregnancyCarePlanServiceImpl.assertReadAccess(patient)"),
        entry("PatientInsuranceController#getPatientInsuranceById", "PatientInsuranceServiceImpl.enforceSelfAccessIfPatient"),
        entry("PatientInsuranceController#getInsurancesByPatientId", "PatientInsuranceServiceImpl.enforceSelfAccessIfPatient"),
        entry("RecordAccessController#optOutStatus", "RecordAccessController.requireSelfUnlessStaff, before any lookup"),
        entry("TreatmentController#getTreatmentById", "a service catalogue entry, not patient data"),
        entry("PatientPortalController#getDocument", "PatientDocumentServiceImpl.requireOwnDocument(caller's patient id)"),
        entry("PatientPortalController#downloadDocument", "PatientDocumentServiceImpl.requireOwnDocument(caller's patient id)"),
        entry("PatientPortalController#getQuestionnaires", "PatientPortalServiceImpl: appointment must be the caller's"),
        entry("PatientPortalController#getMyEducationItem", "PatientPortalServiceImpl.requireAssignedEducation(caller's patient id)"),
        entry("PatientPortalController#myScreeningInstrument", "an instrument definition by code, not patient data"),
        entry("PatientPortalController#getDepartments", "booking directory of a hospital, not patient data"),
        entry("PatientPortalController#getProviders", "booking directory of a hospital, not patient data"));

    @Test
    @DisplayName("every GET a patient can reach by path variable is guarded or owned elsewhere, with a reason")
    void everyPatientReadByIdIsAccountedFor() {
        Set<String> guarded = PatientSubjectReaderRolesMirrorTest.registeredHandlers();
        Set<String> found = patientReadsByPathVariable();

        assertThat(found)
            .as("the scan matched nothing, so the detection is broken, not the surface")
            .isNotEmpty();

        Set<String> unaccounted = new TreeSet<>(found);
        unaccounted.removeAll(guarded);
        unaccounted.removeAll(OWNED_ELSEWHERE.keySet());
        assertThat(unaccounted)
            .as("GET handlers that admit ROLE_PATIENT and take a path variable, with nothing binding a "
                + "patient to their own record. Route each through PatientSubjectReadGuard (and register "
                + "it in PatientSubjectReaderRolesMirrorTest), or, if the service already refuses another "
                + "patient's id exactly as a missing one, add it to OWNED_ELSEWHERE with where it does so")
            .isEmpty();

        Set<String> stale = new TreeSet<>(guarded);
        stale.addAll(OWNED_ELSEWHERE.keySet());
        stale.removeAll(found);
        assertThat(stale)
            .as("listed but no longer a GET that admits ROLE_PATIENT by path variable: remove or rename")
            .isEmpty();

        Set<String> both = new TreeSet<>(guarded);
        both.retainAll(OWNED_ELSEWHERE.keySet());
        assertThat(both).as("an endpoint is either guarded here or owned elsewhere, not both").isEmpty();
    }

    private static Set<String> patientReadsByPathVariable() {
        Set<String> found = new TreeSet<>();
        for (Class<?> controller : controllers()) {
            PreAuthorize classRule = AnnotatedElementUtils.findMergedAnnotation(controller, PreAuthorize.class);
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null || !servesGet(mapping)) {
                    continue;
                }
                PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
                if (rule == null) {
                    rule = classRule;
                }
                if (rule == null || !ADMITS_PATIENT.matcher(rule.value()).find()) {
                    continue;
                }
                if (Arrays.stream(method.getParameters()).anyMatch(PatientSubjectReadCoverageTest::isPathVariable)) {
                    found.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }
        return found;
    }

    private static boolean servesGet(RequestMapping mapping) {
        return mapping.method().length == 0 || Arrays.asList(mapping.method()).contains(RequestMethod.GET);
    }

    private static boolean isPathVariable(Parameter parameter) {
        return AnnotatedElementUtils.hasAnnotation(parameter, PathVariable.class);
    }

    private static List<Class<?>> controllers() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        CachingMetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
        List<Class<?>> found = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources("classpath*:com/example/hms/**/*.class")) {
                MetadataReader reader = factory.getMetadataReader(resource);
                String className = reader.getClassMetadata().getClassName();
                if (className.endsWith("Test") || className.endsWith("IT") || className.contains("$")) {
                    continue;
                }
                // @RestController is meta-annotated with @Controller.
                if (reader.getAnnotationMetadata().hasMetaAnnotation(Controller.class.getName())
                        || reader.getAnnotationMetadata().hasAnnotation(Controller.class.getName())) {
                    found.add(Class.forName(className));
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        return found;
    }
}
