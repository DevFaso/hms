package com.example.hms.controller;

import com.example.hms.config.PreAuthorizeMatcherPairingTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D749: a midwife who requested a consult got a 403 on /assign, and the
 * current consultant handing it over got a 403 on /reassign, because both
 * endpoints admitted only SUPER_ADMIN and DOCTOR. Whoever may CREATE a consult
 * must reach the endpoints that address it (the service decides who actually
 * may); HOSPITAL_ADMIN stays off (decision D5).
 */
class ConsultationAssignGuardTest {

    private static Set<String> roles(String method) {
        Method m = Arrays.stream(ConsultationController.class.getDeclaredMethods())
            .filter(x -> x.getName().equals(method)).findFirst().orElseThrow();
        PreAuthorize pre = AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class);
        assertThat(pre).as(method).isNotNull();
        return PreAuthorizeMatcherPairingTest.rolesAdmittedBy(pre.value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"assignConsultation", "reassignConsultation"})
    @DisplayName("D749: assign/reassign admit every role that may create a consult, and never HOSPITAL_ADMIN")
    void addressingAdmitsTheCreators(String method) {
        Set<String> creators = roles("createConsultation");
        assertThat(roles(method)).containsAll(creators).contains("ROLE_MIDWIFE", "ROLE_NURSE")
            .doesNotContain("ROLE_HOSPITAL_ADMIN");
    }
}
