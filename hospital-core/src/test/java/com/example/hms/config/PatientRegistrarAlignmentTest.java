package com.example.hms.config;

import com.example.hms.controller.PatientController;
import com.example.hms.controller.ReceptionController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Patient registration is two calls: {@code POST /users/admin-register}
 * (the account) then {@code POST /patients} (the record). A role admitted to
 * the first and refused by the second created an orphan account and saw a
 * 403 (QA D55, the doctor). The duplicate check the same form runs must admit
 * the same roles too (QA D40, nurse and midwife).
 */
class PatientRegistrarAlignmentTest {

    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

    private static Set<String> authorities(String spel) {
        Matcher m = QUOTED.matcher(spel);
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static Method annotated(Class<?> type, java.util.function.Predicate<Method> which) {
        return Arrays.stream(type.getDeclaredMethods()).filter(which).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("POST /patients admits exactly the patient registrars")
    void createPatientMatchesRegistrars() {
        Method create = annotated(PatientController.class, m -> {
            PostMapping post = m.getAnnotation(PostMapping.class);
            return post != null && post.value().length == 0 && post.path().length == 0;
        });
        assertThat(authorities(create.getAnnotation(PreAuthorize.class).value()))
            .containsExactlyInAnyOrderElementsOf(Arrays.asList(
                SecurityConstants.authorities(SecurityConstants.USER_REGISTRAR_AUTHORITIES)));
    }

    @Test
    @DisplayName("the registration form's duplicate check admits every patient registrar")
    void duplicateCandidatesAdmitsRegistrars() {
        Method duplicates = annotated(ReceptionController.class, m -> {
            GetMapping get = m.getAnnotation(GetMapping.class);
            return get != null && Arrays.asList(get.value()).contains("/patients/duplicate-candidates");
        });
        Set<String> admitted = authorities(duplicates.getAnnotation(PreAuthorize.class).value());
        assertThat(admitted).containsAll(Arrays.stream(
            SecurityConstants.authorities(SecurityConstants.USER_REGISTRAR_AUTHORITIES)).collect(Collectors.toSet()));
    }
}
