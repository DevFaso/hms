package com.example.hms.controller;

import com.example.hms.payload.dto.LabResultRequestDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.service.LabResultService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LabResultController}.
 * Tests controller-layer logic and verifies @PreAuthorize role assignments
 * as regression guards for Gap 7 and Gap 10 fixes.
 */
@ExtendWith(MockitoExtension.class)
class LabResultControllerTest {

    @Mock
    private LabResultService labResultService;

    @Mock
    private com.example.hms.service.CriticalValueNotificationService criticalValueNotificationService;

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private LabResultController controller;

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<String> extractRolesFromMethod(String methodName, Class<?>... paramTypes) throws Exception {
        Method method = LabResultController.class.getDeclaredMethod(methodName, paramTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("@PreAuthorize on %s", methodName).isNotNull();

        Pattern p = Pattern.compile("'(\\w+)'");
        Matcher m = p.matcher(annotation.value());
        List<String> roles = new ArrayList<>();
        while (m.find()) {
            roles.add(m.group(1));
        }
        return roles;
    }

    // ── createLabResult endpoint ─────────────────────────────────────────────

    @Test
    void createLabResult_returns201() {
        LabResultResponseDTO dto = LabResultResponseDTO.builder().id(UUID.randomUUID().toString()).build();
        when(labResultService.createLabResult(any(), any())).thenReturn(dto);

        ResponseEntity<LabResultResponseDTO> result = controller.createLabResult(
                new LabResultRequestDTO(), Locale.ENGLISH);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isNotNull();
    }

    @Test
    void createLabResult_preAuthorize_includesLabDirector() throws Exception {
        List<String> roles = extractRolesFromMethod("createLabResult",
                LabResultRequestDTO.class, Locale.class);
        assertThat(roles).contains("LAB_DIRECTOR");
    }

    @Test
    void createLabResult_preAuthorize_includesQualityManager() throws Exception {
        List<String> roles = extractRolesFromMethod("createLabResult",
                LabResultRequestDTO.class, Locale.class);
        assertThat(roles).contains("QUALITY_MANAGER");
    }

    @Test
    void createLabResult_preAuthorize_includesOriginalRoles() throws Exception {
        List<String> roles = extractRolesFromMethod("createLabResult",
                LabResultRequestDTO.class, Locale.class);
        assertThat(roles).contains("DOCTOR", "LAB_SCIENTIST", "LAB_TECHNICIAN", "LAB_MANAGER", "NURSE", "MIDWIFE");
    }

    // ── updateLabResult endpoint ─────────────────────────────────────────────

    @Test
    void updateLabResult_preAuthorize_includesLabDirector() throws Exception {
        List<String> roles = extractRolesFromMethod("updateLabResult",
                UUID.class, LabResultRequestDTO.class, Locale.class);
        assertThat(roles).contains("LAB_DIRECTOR");
    }

    @Test
    void updateLabResult_preAuthorize_includesQualityManager() throws Exception {
        List<String> roles = extractRolesFromMethod("updateLabResult",
                UUID.class, LabResultRequestDTO.class, Locale.class);
        assertThat(roles).contains("QUALITY_MANAGER");
    }

    // ── critical-escalation manual trigger (P0 #5) ───────────────────────────

    @Test
    void runCriticalEscalationSweep_returnsCount() {
        when(criticalValueNotificationService.escalateOverdue()).thenReturn(3);

        var response = controller.runCriticalEscalationSweep();

        assertThat(response.getBody()).containsEntry("escalated", 3);
    }

    @Test
    void runCriticalEscalationSweep_preAuthorize_isAdminOnly() throws Exception {
        List<String> roles = extractRolesFromMethod("runCriticalEscalationSweep");
        assertThat(roles).containsExactlyInAnyOrder("HOSPITAL_ADMIN", "SUPER_ADMIN");
    }

    // ── getAllLabResults must include SUPER_ADMIN for cross-tenant browsing ──

    @Test
    void getAllLabResults_preAuthorize_includesSuperAdmin() throws Exception {
        List<String> roles = extractRolesFromMethod("getAllLabResults",
                Pageable.class, Locale.class);
        assertThat(roles).contains("SUPER_ADMIN");
    }

    // ── releaseLabResult should NOT include LAB_TECHNICIAN (Gap 11 — intentional) ──

    @Test
    void releaseLabResult_preAuthorize_excludesLabTechnician() throws Exception {
        List<String> roles = extractRolesFromMethod("releaseLabResult",
                UUID.class, Locale.class);
        assertThat(roles).isNotEmpty()
                .doesNotContain("LAB_TECHNICIAN");
    }
}
