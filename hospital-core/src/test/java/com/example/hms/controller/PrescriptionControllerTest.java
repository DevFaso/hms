package com.example.hms.controller;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PrescriptionControllerTest {

    private static List<String> extractRolesFromMethod(String methodName, Class<?>... paramTypes) throws Exception {
        Method method = PrescriptionController.class.getDeclaredMethod(methodName, paramTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("@PreAuthorize on %s", methodName).isNotNull();

        Pattern p = Pattern.compile("'(?:ROLE_)?(\\w+)'");
        Matcher m = p.matcher(annotation.value());
        List<String> roles = new ArrayList<>();
        while (m.find()) {
            roles.add(m.group(1));
        }
        return roles;
    }

    /**
     * G5: the two clarification endpoints are gated by their annotations
     * alone. SecurityConfig declares no matcher for /prescriptions, so the
     * path rides anyRequest().authenticated() — and a matcher added later
     * that forgot the pharmacist roles would 403 the pharmacist before the
     * annotation ran (first-match-wins, terminal).
     */
    @Test
    void requestClarification_preAuthorize_isPharmacistOnly() throws Exception {
        List<String> roles = extractRolesFromMethod("requestClarification",
                UUID.class, com.example.hms.payload.dto.PrescriptionClarificationRequestDTO.class, Locale.class);
        assertThat(roles).containsExactlyInAnyOrder("PHARMACIST", "PHARMACY_VERIFIER", "SUPER_ADMIN");
    }

    @Test
    void resolveClarification_preAuthorize_isDoctorOnly() throws Exception {
        List<String> roles = extractRolesFromMethod("resolveClarification",
                UUID.class, com.example.hms.payload.dto.PrescriptionClarificationResolutionDTO.class, Locale.class);
        assertThat(roles).containsExactly("DOCTOR");
    }

    @Test
    void securityConfig_declaresNoPrescriptionsMatcher_soTheAnnotationsAreTheGate() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Paths.get("src/main/java/com/example/hms/config/SecurityConfig.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(source)
                .as("a /prescriptions matcher must admit every role the controller annotations admit")
                .doesNotContain("\"/prescriptions");
    }

    @Test
    void list_preAuthorize_includesSuperAdmin() throws Exception {
        List<String> roles = extractRolesFromMethod("list",
                UUID.class, UUID.class, UUID.class, Pageable.class, Locale.class);
        assertThat(roles).contains("SUPER_ADMIN");
    }

    @Test
    void list_preAuthorize_retainsTenantRoles() throws Exception {
        List<String> roles = extractRolesFromMethod("list",
                UUID.class, UUID.class, UUID.class, Pageable.class, Locale.class);
        assertThat(roles).contains("DOCTOR", "NURSE", "MIDWIFE", "PHARMACIST").doesNotContain("HOSPITAL_ADMIN");
    }
}
