package com.example.hms.security.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design §3.4's second scan: every controller method that receives a
 * {@code hospitalId} path variable or request parameter acts at that hospital.
 * {@link HospitalIdNarrowingInterceptor} narrows the scope for all of them
 * before the handler runs, so the only way out is {@link HospitalScopeExempt}
 * with a reason — and that set is frozen here: a new exemption fails this test
 * until it is added below, deliberately.
 *
 * <p>Classes are read by a metadata scan, not a component scan: a component
 * scan evaluates {@code @ConditionalOnProperty} and silently drops the
 * controllers that are off by default.
 */
class HospitalIdParameterCoverageTest {

    /** Handler → why its {@code hospitalId} is not its acting scope. Frozen: it only shrinks. */
    private static final Map<String, String> EXEMPT = Map.of(
        "DepartmentController.getActiveDepartmentsMinimal",
        "a referral lists the receiving hospital's departments: a directory, not the acting scope",
        "PatientPortalController.getDepartments",
        "a patient choosing where to book: the hospital is the booking target",
        "PatientPortalController.getProviders",
        "a patient choosing where to book: the hospital is the booking target");

    @Test
    @DisplayName("every handler taking a hospitalId is narrowed to it, or exempt with a reason from the frozen list")
    void everyHospitalIdHandlerIsNarrowedOrReasonablyExempt() {
        Map<String, String> exempt = new TreeMap<>();
        TreeSet<String> narrowed = new TreeSet<>();
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class) == null
                    || !takesHospitalId(method)) {
                    continue;
                }
                String key = controller.getSimpleName() + "." + method.getName();
                HospitalScopeExempt exemption = AnnotatedElementUtils.findMergedAnnotation(method, HospitalScopeExempt.class);
                if (exemption == null) {
                    exemption = AnnotatedElementUtils.findMergedAnnotation(controller, HospitalScopeExempt.class);
                }
                if (exemption != null) {
                    assertThat(exemption.reason()).as("%s exempt without a reason", key).isNotBlank();
                    exempt.put(key, exemption.reason());
                } else {
                    narrowed.add(key);
                }
            }
        }
        assertThat(exempt.keySet())
            .as("exempt handlers: a new one is a decision, add it to EXEMPT with its reason")
            .containsExactlyInAnyOrderElementsOf(EXEMPT.keySet());
        // The scan found what the design inventoried (190 handler parameters at 304489ede).
        assertThat(narrowed.size() + exempt.size()).isGreaterThanOrEqualTo(180);
    }

    static boolean takesHospitalId(Method method) {
        for (Parameter parameter : method.getParameters()) {
            PathVariable pathVariable = AnnotatedElementUtils.findMergedAnnotation(parameter, PathVariable.class);
            if (pathVariable != null && HospitalIdNarrowingInterceptor.HOSPITAL_ID.equals(
                declared(pathVariable.name(), parameter))) {
                return true;
            }
            RequestParam requestParam = AnnotatedElementUtils.findMergedAnnotation(parameter, RequestParam.class);
            if (requestParam != null && HospitalIdNarrowingInterceptor.HOSPITAL_ID.equals(
                declared(requestParam.name(), parameter))) {
                return true;
            }
        }
        return false;
    }

    private static String declared(String name, Parameter parameter) {
        return name == null || name.isBlank() ? parameter.getName() : name;
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
                if (reader.getAnnotationMetadata().hasAnnotation(RestController.class.getName())) {
                    found.add(Class.forName(className));
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        return found;
    }
}
