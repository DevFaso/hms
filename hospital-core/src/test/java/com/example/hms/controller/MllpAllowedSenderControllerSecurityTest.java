package com.example.hms.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Contract test for the {@code ROLE_SUPER_ADMIN} gate on
 * {@link MllpAllowedSenderController}.
 *
 * <p>The sibling {@code MllpAllowedSenderControllerTest} runs with
 * {@code addFilters = false} for fast behavioural coverage and
 * therefore cannot detect a regression where the
 * {@code @PreAuthorize} is dropped or relaxed. This reflection-based
 * test is a small, dependency-free guard that fails immediately if
 * the gate is removed, weakened, or moved off the class.
 *
 * <p>Why class-level rather than per-method: the controller declares
 * the gate once on the type so every endpoint inherits it. Adding a
 * new admin endpoint will not silently bypass the check unless
 * someone deliberately overrides it.
 */
class MllpAllowedSenderControllerSecurityTest {

    @Test
    @DisplayName("controller carries class-level @PreAuthorize requiring ROLE_SUPER_ADMIN")
    void preAuthorizePresentOnClass() {
        PreAuthorize annotation = MllpAllowedSenderController.class.getAnnotation(PreAuthorize.class);
        assertThat(annotation)
            .as("MllpAllowedSenderController must keep its @PreAuthorize gate")
            .isNotNull();
        assertThat(annotation.value())
            .as("admin allowlist CRUD must be gated by ROLE_SUPER_ADMIN")
            .contains("ROLE_SUPER_ADMIN")
            .contains("hasAuthority");
    }

    @Test
    @DisplayName("no controller method weakens or overrides the class-level @PreAuthorize")
    void noMethodOverridesGate() {
        for (var method : MllpAllowedSenderController.class.getDeclaredMethods()) {
            PreAuthorize methodAnnotation = method.getAnnotation(PreAuthorize.class);
            if (methodAnnotation != null) {
                assertThat(methodAnnotation.value())
                    .as("method %s must not weaken the class-level gate", method.getName())
                    .contains("ROLE_SUPER_ADMIN");
            }
        }
    }
}
