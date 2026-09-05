package com.example.hms.security.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two things about {@link WriteAuditInterceptor}'s convention that must stay
 * true as controllers are added.
 *
 * <p>First, an opt-out is never silent: {@code @WriteAudited(skip = true)}
 * without a {@code reason} fails here, so the next reader knows which
 * specific event replaces the generic row. Second, the convention still has
 * work to do — if every write handler were opted out the interceptor would
 * be decoration, and this test says so.
 */
@DisplayName("Write-audit coverage")
class WriteAuditCoverageTest {

    private static final String CONTROLLER_PACKAGE = "com.example.hms.controller";

    @Test
    @DisplayName("every opted-out write surface says why")
    void everyOptOutHasAReason() {
        Set<String> silent = new TreeSet<>();
        for (Class<?> controller : controllers()) {
            WriteAudited onClass = AnnotatedElementUtils.findMergedAnnotation(controller, WriteAudited.class);
            if (onClass != null && onClass.skip() && onClass.reason().isBlank()) {
                silent.add(controller.getSimpleName());
            }
            for (Method method : controller.getDeclaredMethods()) {
                WriteAudited onMethod = AnnotatedElementUtils.findMergedAnnotation(method, WriteAudited.class);
                if (onMethod != null && onMethod.skip() && onMethod.reason().isBlank()) {
                    silent.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }
        assertThat(silent)
            .withFailMessage("@WriteAudited(skip = true) needs a reason on: %s", silent)
            .isEmpty();
    }

    @Test
    @DisplayName("most write handlers are recorded by convention, not opted out")
    void theConventionCarriesTheLoad() {
        int recorded = 0;
        int skipped = 0;
        for (Class<?> controller : controllers()) {
            WriteAudited onClass = AnnotatedElementUtils.findMergedAnnotation(controller, WriteAudited.class);
            for (Method method : controller.getDeclaredMethods()) {
                if (!isWrite(method)) {
                    continue;
                }
                WriteAudited onMethod = AnnotatedElementUtils.findMergedAnnotation(method, WriteAudited.class);
                WriteAudited effective = onMethod != null ? onMethod : onClass;
                if (effective != null && effective.skip()) {
                    skipped++;
                } else {
                    recorded++;
                }
            }
        }
        assertThat(recorded).isGreaterThan(skipped).isGreaterThan(200);
    }

    private static boolean isWrite(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, PostMapping.class)
            || AnnotatedElementUtils.hasAnnotation(method, PutMapping.class)
            || AnnotatedElementUtils.hasAnnotation(method, PatchMapping.class)
            || AnnotatedElementUtils.hasAnnotation(method, DeleteMapping.class);
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
                throw new IllegalStateException(e);
            }
        }
        return found;
    }
}
