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

    /**
     * QA round 33: the two writes answer a refused role at the edge, because a
     * @Valid body is read before @PreAuthorize (a receptionist read a 400 about
     * the frequency). This asserted no /prescriptions matcher at all; its reason
     * stands: a matcher must admit every role the annotation admits, so each is
     * held to the annotation's exact roles, and no other /prescriptions matcher
     * exists (every other route's annotation is its gate).
     */
    @Test
    void securityConfig_declaresOnlyTheTwoWriteMatchers_withTheAnnotationsRoles() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Paths.get("src/main/java/com/example/hms/config/SecurityConfig.java"),
                java.nio.charset.StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        String roles = ".hasAnyAuthority(ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE)";
        assertThat(source)
                .as("a /prescriptions matcher must admit every role the controller annotations admit")
                .contains("requestMatchers(HttpMethod.POST, \"/prescriptions\") " + roles)
                .contains("requestMatchers(HttpMethod.PUT, \"/prescriptions/*\") " + roles);
        assertThat(source.split("\"/prescriptions", -1)).as("no other /prescriptions matcher").hasSize(3);
    }

    @Test
    void list_preAuthorize_includesSuperAdmin() throws Exception {
        assertThat(listRoles()).contains("SUPER_ADMIN");
    }

    @Test
    void list_preAuthorize_retainsTenantRoles() throws Exception {
        assertThat(listRoles()).contains("DOCTOR", "NURSE", "MIDWIFE", "PHARMACIST")
                .doesNotContain("HOSPITAL_ADMIN");
    }

    /* ── Gap G9: the verifier reads ────────────────────────────────────── */

    /**
     * ROLE_PHARMACY_VERIFIER holds {@code /pharmacist-verify} and
     * {@code /request-clarification}. Both are judgments about the order as
     * written, and neither is possible without reading it — the role could
     * not even read the prescriber's answer to its own question. The nav
     * entry in the portal shell is gated on exactly these two reads.
     */
    @Test
    void list_preAuthorize_admitsThePharmacyVerifier() throws Exception {
        assertThat(listRoles()).contains("PHARMACY_VERIFIER");
    }

    @Test
    void getById_preAuthorize_admitsThePharmacyVerifier() throws Exception {
        List<String> roles = extractRolesFromMethod("getById",
                UUID.class, org.springframework.security.core.Authentication.class, Locale.class);
        assertThat(roles).contains("PHARMACY_VERIFIER", "DOCTOR", "NURSE", "MIDWIFE",
                "PHARMACIST", "PATIENT");
    }

    /**
     * Whoever may RAISE a clarification must read the answer, so every role on
     * {@code requestClarification} is a clinical reader — otherwise a
     * pharmacist who is also a patient of the hospital would get the copy with
     * the exchange stripped out of it.
     */
    @Test
    void everyClarificationRequesterIsAClinicalReader() throws Exception {
        List<String> requesters = extractRolesFromMethod("requestClarification",
                UUID.class, com.example.hms.payload.dto.PrescriptionClarificationRequestDTO.class, Locale.class);
        for (String role : requesters) {
            if ("SUPER_ADMIN".equals(role)) {
                continue; // expanded into ROLE_DOCTOR by RoleExpansion before any check runs
            }
            assertThat(com.example.hms.service.PrescriptionReaderRoles.CLINICAL_READER_ROLES)
                    .as("%s may ask a question and must therefore read the answer", role)
                    .contains("ROLE_" + role);
        }
    }

    private static List<String> listRoles() throws Exception {
        return extractRolesFromMethod("list",
                UUID.class, UUID.class, UUID.class, List.class, Pageable.class, Locale.class);
    }

    /**
     * QA D706; QA D1318 (round 29, assertion changed): the quantity is required
     * on create by the body's OnCreate constraint, refused at the edge with the
     * other missing fields (it was a check in the handler, with no field, after them).
     */
    @org.junit.jupiter.api.Test
    void qaD706_createWithoutQuantityIsRefusedBeforeTheService() throws Exception {
        com.example.hms.payload.dto.PrescriptionRequestDTO request = new com.example.hms.payload.dto.PrescriptionRequestDTO();
        request.setMedicationName("Morphine 10 mg");
        try (jakarta.validation.ValidatorFactory factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            org.assertj.core.api.Assertions.assertThat(factory.getValidator().validate(request,
                    jakarta.validation.groups.Default.class, com.example.hms.validation.OnCreate.class))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("quantity", "patientId");
        }
        java.lang.reflect.Parameter body = PrescriptionController.class
            .getMethod("create", com.example.hms.payload.dto.PrescriptionRequestDTO.class, Locale.class).getParameters()[0];
        org.assertj.core.api.Assertions.assertThat(body.getAnnotation(org.springframework.validation.annotation.Validated.class).value())
            .contains(com.example.hms.validation.OnCreate.class);
    }
}
