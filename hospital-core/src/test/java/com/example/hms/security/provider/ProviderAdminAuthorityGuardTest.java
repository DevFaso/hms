package com.example.hms.security.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider plan T5 / §3.3: PROVIDER_ADMIN appears in no hospital guard. An
 * outside business's administrator must not inherit anything a hospital
 * endpoint grants by authority, so the only {@code @PreAuthorize} that may name
 * it is the admin-register one (through
 * {@code SecurityConstants.PROVIDER_REGISTRAR_AUTHORITIES}), plus the provider
 * endpoints a later slice adds here with a reason.
 *
 * <p>A guard test: it has no change to revert. It fails when a new annotation
 * names the role without being listed below.
 */
class ProviderAdminAuthorityGuardTest {

    /** "Class#method" handlers allowed to name PROVIDER_ADMIN, each with its reason. */
    private static final Set<String> ALLOWED = Set.of(
        // The provider registrar path (plan §6.3): what it may grant is
        // UserAccountAccess.requireMayGrant plus the facility guard.
        "UserController#adminRegister"
    );

    @Test
    @DisplayName("no controller guard names PROVIDER_ADMIN except the allowed provider paths")
    void noHospitalGuardNamesProviderAdmin() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (BeanDefinition definition : scanner.findCandidateComponents("com.example.hms")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            scanned++;
            PreAuthorize onClass = AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class);
            if (onClass != null && namesProviderAdmin(onClass.value())) {
                offenders.add(type.getSimpleName() + " (class level)");
            }
            for (Method method : type.getDeclaredMethods()) {
                PreAuthorize annotation = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
                String handler = type.getSimpleName() + "#" + method.getName();
                if (annotation != null && namesProviderAdmin(annotation.value()) && !ALLOWED.contains(handler)) {
                    offenders.add(handler);
                }
            }
        }
        assertThat(scanned).as("controllers scanned").isGreaterThan(100);
        assertThat(offenders).as("guards naming PROVIDER_ADMIN outside the provider paths").isEmpty();
    }

    @Test
    @DisplayName("the allowed admin-register guard really names it (the list is not stale)")
    void allowedEntryIsLive() throws Exception {
        Class<?> controller = Class.forName("com.example.hms.controller.UserController");
        boolean found = false;
        for (Method method : controller.getDeclaredMethods()) {
            PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
            if ("adminRegister".equals(method.getName()) && annotation != null) {
                found = namesProviderAdmin(annotation.value());
            }
        }
        assertThat(found).isTrue();
    }

    private static boolean namesProviderAdmin(String expression) {
        return expression != null && expression.contains("PROVIDER_ADMIN");
    }
}
