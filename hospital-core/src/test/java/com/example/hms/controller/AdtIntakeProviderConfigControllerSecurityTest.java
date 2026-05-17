package com.example.hms.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Contract test for the {@code ROLE_SUPER_ADMIN} gate on
 * {@link AdtIntakeProviderConfigController}. Mirrors the
 * {@code MllpAllowedSenderControllerSecurityTest} shape — the gate
 * lives once on the class so reflection is sufficient to verify it.
 */
class AdtIntakeProviderConfigControllerSecurityTest {

    @Test
    @DisplayName("controller carries class-level @PreAuthorize requiring ROLE_SUPER_ADMIN")
    void preAuthorizePresentOnClass() {
        PreAuthorize annotation =
            AdtIntakeProviderConfigController.class.getAnnotation(PreAuthorize.class);
        assertThat(annotation)
            .as("AdtIntakeProviderConfigController must keep its @PreAuthorize gate")
            .isNotNull();
        assertThat(annotation.value())
            .as("admin intake-config CRUD must be gated by ROLE_SUPER_ADMIN")
            .contains("ROLE_SUPER_ADMIN")
            .contains("hasAuthority");
    }

    @Test
    @DisplayName("no controller method weakens or overrides the class-level @PreAuthorize")
    void noMethodOverridesGate() {
        for (var method : AdtIntakeProviderConfigController.class.getDeclaredMethods()) {
            PreAuthorize methodAnnotation = method.getAnnotation(PreAuthorize.class);
            if (methodAnnotation != null) {
                assertThat(methodAnnotation.value())
                    .as("method %s must not weaken the class-level gate", method.getName())
                    .contains("ROLE_SUPER_ADMIN");
            }
        }
    }
}
