package com.example.hms.controller;

import com.example.hms.config.PreAuthorizeMatcherPairingTest;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who reaches the imaging key-image endpoints (V316), role by role, by
 * evaluating the very {@code @PreAuthorize} strings Spring evaluates. The
 * images are read exactly as the report is read, and written as a report is
 * authored; reception, billing and the other non-clinical roles reach neither.
 * Hospital scope and the released-only rules are behavioural:
 * {@code ImagingStudyImageServiceTest}.
 */
@DisplayName("Imaging key images: the role matrix")
class ImagingStudyImageAccessTest {

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

    @ParameterizedTest(name = "{0}: read={1}, write={2}")
    @CsvSource({
        "ROLE_RADIOLOGIST, true, true",
        "ROLE_DOCTOR, true, true",
        "ROLE_NURSE, true, false",
        "ROLE_MIDWIFE, true, false",
        "ROLE_HOSPITAL_ADMIN, true, false",
        // Platform admin rules 2026-11-20, rule 1b: the organisation admin reads no clinical data.
        "ROLE_ADMIN, false, false",
        "ROLE_PATIENT, true, false",
        "ROLE_RECEPTIONIST, false, false",
        "ROLE_BILLING_SPECIALIST, false, false",
        "ROLE_ACCOUNTANT, false, false",
        "ROLE_CLAIMS_REVIEWER, false, false",
        "ROLE_PHARMACIST, false, false",
        "ROLE_LAB_TECHNICIAN, false, false",
        "ROLE_STAFF, false, false"
    })
    void eachRole(String role, boolean reads, boolean writes) {
        Authentication caller = token(role);
        for (String read : new String[] {"list", "content"}) {
            assertThat(admits(read, caller)).as(role + " reads (" + read + ")").isEqualTo(reads);
        }
        for (String write : new String[] {"upload", "delete"}) {
            assertThat(admits(write, caller)).as(role + " writes (" + write + ")").isEqualTo(writes);
        }
    }

    @Test
    @DisplayName("a super-admin reads, and writes only through a clinical role of their own")
    void superAdmin() {
        Authentication superAdmin = token("ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN", "ROLE_RECEPTIONIST",
            "ROLE_DOCTOR", "ROLE_NURSE", "ROLE_STAFF", "ROLE_PATIENT");
        HospitalContextHolder.setContext(com.example.hms.security.context.HospitalContext.builder()
            .assignedRoles(Set.of("ROLE_SUPER_ADMIN")).build());
        assertThat(admits("list", superAdmin)).isTrue();
        assertThat(admits("upload", superAdmin)).as("the inherited DOCTOR is not theirs").isFalse();
        assertThat(admits("delete", superAdmin)).isFalse();
    }

    @Test
    @DisplayName("the reads admit exactly the report's readers, the writes exactly its authors")
    void mirrorsTheReport() {
        Set<String> imageReaders = roles(ImagingStudyImageController.class, "list");
        assertThat(roles(ImagingStudyImageController.class, "content")).isEqualTo(imageReaders);
        assertThat(imageReaders).isEqualTo(roles(ImagingResultController.class, "getLatestReportForOrder"));
        // Policy rule 3 (2026-11-20, provisional): the report's authors and the imaging technician, who
        // attaches the study's key images; withdrawing after the signature stays the authors' alone.
        Set<String> authors = roles(ImagingResultController.class, "createReport");
        Set<String> imageWriters = new java.util.TreeSet<>(authors);
        imageWriters.add("ROLE_IMAGING_TECHNICIAN");
        for (String write : new String[] {"upload", "delete", "updateCaption"}) {
            assertThat(roles(ImagingStudyImageController.class, write)).as(write).isEqualTo(imageWriters);
            assertThat(gate(ImagingStudyImageController.class, write)).as(write)
                .startsWith("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication,");
        }
        assertThat(gate(ImagingStudyImageController.class, "withdraw"))
            .isEqualTo(gate(ImagingResultController.class, "createReport"));
        assertThat(roles(ImagingResultController.class, "createReport")).doesNotContain("ROLE_IMAGING_TECHNICIAN");
    }

    private static boolean admits(String handler, Authentication caller) {
        SecurityExpressionRoot root = new SecurityExpressionRoot(caller) { };
        StandardEvaluationContext context = new StandardEvaluationContext(root);
        Boolean classGate = new SpelExpressionParser()
            .parseExpression(ImagingStudyImageController.class.getAnnotation(PreAuthorize.class).value())
            .getValue(context, Boolean.class);
        Boolean methodGate = new SpelExpressionParser()
            .parseExpression(gate(ImagingStudyImageController.class, handler))
            .getValue(context, Boolean.class);
        // Spring evaluates the method annotation; the class one (the union) must never be narrower.
        assertThat(!Boolean.TRUE.equals(methodGate) || Boolean.TRUE.equals(classGate))
            .as("the class gate admits whoever " + handler + " admits").isTrue();
        return Boolean.TRUE.equals(methodGate);
    }

    private static Authentication token(String... authorities) {
        return new UsernamePasswordAuthenticationToken("u", "n/a",
            Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    private static String gate(Class<?> controller, String handler) {
        Method method = Arrays.stream(controller.getDeclaredMethods())
            .filter(m -> m.getName().equals(handler)).findFirst()
            .orElseThrow(() -> new AssertionError(controller.getSimpleName() + "#" + handler + " is gone"));
        PreAuthorize gate = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        assertThat(gate).as(handler + " carries its own @PreAuthorize").isNotNull();
        return gate.value().trim();
    }

    private static Set<String> roles(Class<?> controller, String handler) {
        return new LinkedHashSet<>(PreAuthorizeMatcherPairingTest.rolesAdmittedBy(gate(controller, handler)));
    }
}
