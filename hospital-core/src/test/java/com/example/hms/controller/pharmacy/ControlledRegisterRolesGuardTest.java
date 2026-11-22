package com.example.hms.controller.pharmacy;

import com.example.hms.service.pharmacy.ControlledStockWitness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3411 (r70): the pharmacy verifier could not witness a controlled-drug movement nor read the register. The
 * class: whoever may sign a controlled movement as its witness reads the register that shows it, on every
 * endpoint of the register (page, CSV and PDF).
 */
class ControlledRegisterRolesGuardTest {

    @Test
    @DisplayName("every controlled-stock witness role reads every controlled-register endpoint")
    void everyWitnessRoleReadsTheRegister() {
        Method[] endpoints = Arrays.stream(ControlledRegisterController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PreAuthorize.class))
                .toArray(Method[]::new);
        assertThat(endpoints).hasSizeGreaterThanOrEqualTo(3);
        for (Method endpoint : endpoints) {
            String rule = endpoint.getAnnotation(PreAuthorize.class).value();
            for (String role : ControlledStockWitness.WITNESS_ROLE_CODES) {
                assertThat(rule).as("%s reads by %s", endpoint.getName(), role)
                        .contains("'" + role.replaceFirst("^ROLE_", "") + "'");
            }
        }
    }

    @Test
    @DisplayName("the pharmacy verifier, the pharmacy's checking role, may witness a controlled movement")
    void theVerifierWitnesses() {
        assertThat(ControlledStockWitness.WITNESS_ROLE_CODES).contains("ROLE_PHARMACY_VERIFIER");
    }
}
