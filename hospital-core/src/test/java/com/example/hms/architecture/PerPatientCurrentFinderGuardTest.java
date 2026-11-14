package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1850 guard (round 53): {@code GET /me/patient/care-team} answered 500 for a patient with a
 * current doctor at two hospitals. #1096 allowed one current primary-care link per hospital, but
 * {@code PatientPrimaryCareRepository.findByPatient_IdAndCurrentTrue} still returned an
 * {@code Optional}, and Spring Data throws {@code IncorrectResultSizeDataAccessException} the day
 * a second row exists. A patient registered at several hospitals has one "current" or "active"
 * row per hospital for every per-hospital record.
 *
 * <p>Every repository method that returns an {@code Optional} of a patient's current or active
 * row must either name a hospital (a {@code Hospital} in its name or a {@code hospital…}
 * parameter), limit itself ({@code findFirst…}, {@code findTop…}, or {@code LIMIT} in its query),
 * or be listed below with the database constraint that keeps it to one row. Otherwise it returns
 * a list.
 */
@DisplayName("A patient's current/active finder never returns an Optional over one row per hospital")
class PerPatientCurrentFinderGuardTest {

    private static final String REPOSITORY_PACKAGE = "com.example.hms.repository";

    /** Methods a database constraint keeps to one row whatever the hospital count, with that constraint. */
    private static final Map<String, String> ONE_ROW_BY_CONSTRAINT = Map.of(
        "IsolationPrecautionRepository.findActiveOfType",
        "uk_isolation_active_type_per_patient (V136): one active row per patient and type, across hospitals");

    @Test
    @DisplayName("every Optional current/active finder of a patient is hospital-scoped, limited or unique")
    void noUnscopedOptionalCurrentFinder() throws ClassNotFoundException {
        List<Class<?>> repositories = repositories();
        assertThat(repositories).as("repository interfaces scanned").hasSizeGreaterThan(100);

        List<String> offenders = new ArrayList<>();
        for (Class<?> repository : repositories) {
            offenders.addAll(offenders(repository));
        }
        assertThat(offenders)
            .as("an Optional over a patient's current/active rows throws once the patient has one per hospital "
                + "(QA D1850: the care team answered 500). Return a List, scope it to a hospital, use findFirst, "
                + "or list the method in ONE_ROW_BY_CONSTRAINT with the constraint that keeps it unique")
            .isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D1850 shape and passes the safe ones")
    void catchesTheD1850Shape() {
        assertThat(offenders(D1850Shape.class)).containsExactly("D1850Shape.findByPatient_IdAndCurrentTrue");
    }

    /** The finder D1850 shipped, beside the shapes that are safe. */
    @SuppressWarnings("unused")
    interface D1850Shape {
        Optional<Object> findByPatient_IdAndCurrentTrue(java.util.UUID patientId);

        Optional<Object> findCurrentByPatientAndHospital(@Param("patientId") java.util.UUID patientId,
                                                         @Param("hospitalId") java.util.UUID hospitalId);

        Optional<Object> findFirstByPatient_IdAndActiveTrueOrderByCreatedAtDesc(java.util.UUID patientId);

        List<Object> findByPatient_IdAndCurrentTrueOrderByStartDateDesc(java.util.UUID patientId);

        @Query("SELECT a FROM Admission a WHERE a.patient.id = :patientId AND a.status = 'ACTIVE' LIMIT 1")
        Optional<Object> findCurrentAdmissionByPatient(@Param("patientId") java.util.UUID patientId);

        Optional<Object> findByIdAndActiveTrue(java.util.UUID id);
    }

    static List<String> offenders(Class<?> repository) {
        List<String> offenders = new ArrayList<>();
        for (Method method : repository.getDeclaredMethods()) {
            String key = repository.getSimpleName() + "." + method.getName();
            if (method.isDefault() || method.isSynthetic() || method.getReturnType() != Optional.class
                || !currentOrActive(method) || !perPatient(method) || hospitalScoped(method) || limited(method)
                || ONE_ROW_BY_CONSTRAINT.containsKey(key)) {
                continue;
            }
            offenders.add(key);
        }
        return offenders;
    }

    private static boolean currentOrActive(Method method) {
        String name = method.getName().toLowerCase(Locale.ROOT);
        Query query = method.getAnnotation(Query.class);
        String jpql = query == null ? "" : query.value().toLowerCase(Locale.ROOT);
        return name.contains("current") || name.contains("active")
            || jpql.contains("current") || jpql.contains("active") || jpql.contains("endedat is null");
    }

    private static boolean perPatient(Method method) {
        if (method.getName().toLowerCase(Locale.ROOT).contains("patient")) {
            return true;
        }
        for (Parameter parameter : method.getParameters()) {
            Param param = parameter.getAnnotation(Param.class);
            if (param != null && param.value().toLowerCase(Locale.ROOT).contains("patient")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hospitalScoped(Method method) {
        if (method.getName().toLowerCase(Locale.ROOT).contains("hospital")) {
            return true;
        }
        for (Parameter parameter : method.getParameters()) {
            Param param = parameter.getAnnotation(Param.class);
            if (param != null && param.value().toLowerCase(Locale.ROOT).contains("hospital")) {
                return true;
            }
        }
        return false;
    }

    private static boolean limited(Method method) {
        String name = method.getName();
        if (name.startsWith("findFirst") || name.startsWith("findTop")) {
            return true;
        }
        Query query = method.getAnnotation(Query.class);
        return query != null && query.value().toUpperCase(Locale.ROOT).matches("(?s).*\\bLIMIT\\s+1\\b.*");
    }

    private static List<Class<?>> repositories() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(REPOSITORY_PACKAGE)) {
            found.add(Class.forName(definition.getBeanClassName()));
        }
        return found;
    }
}
