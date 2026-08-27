package com.example.hms.security.audit;

import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.service.AuditEventLogService;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the interceptor through a real Spring MVC dispatch.
 *
 * <p>Every other test of this interceptor calls {@code afterCompletion}
 * directly, which quietly assumes the two things the whole design rests on:
 * that Spring populates {@code URI_TEMPLATE_VARIABLES_ATTRIBUTE} with the
 * resolved path variables, and {@code BEST_MATCHING_PATTERN_ATTRIBUTE} with
 * the template rather than the concrete URI. If either assumption were wrong,
 * every one of those tests would still pass and nothing would ever be
 * recorded — the exact shape of failure this interceptor exists to end.
 */
@DisplayName("Patient-access audit, through a real dispatch")
class PatientAccessAuditDispatchTest {

    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();

    @RestController
    static class ChartController {
        @GetMapping("/patients/{id}/allergies")
        ResponseEntity<String> allergies(@PathVariable UUID id) {
            return ResponseEntity.ok("[]");
        }

        @GetMapping("/encounters/patient/{patientId}")
        ResponseEntity<String> encounters(@PathVariable UUID patientId) {
            return ResponseEntity.ok("[]");
        }

        @GetMapping("/appointments/{id}")
        ResponseEntity<String> appointment(@PathVariable UUID id) {
            return ResponseEntity.ok("{}");
        }
    }

    private AuditEventLogService auditService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditEventLogService.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<AuditEventLogService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(auditService);

        PatientAccessAuditInterceptor interceptor =
            new PatientAccessAuditInterceptor(provider, 30, 1000);
        ReflectionTestUtils.setField(interceptor, "enabled", true);

        mockMvc = MockMvcBuilders.standaloneSetup(new ChartController())
            .addInterceptors(interceptor)
            .build();

        CustomUserDetails principal = new CustomUserDetails(
            DOCTOR, "dr.kabore", "x", true, List.of(new SimpleGrantedAuthority("ROLE_DOCTOR")));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AuditEventRequestDTO captureEmitted() {
        ArgumentCaptor<AuditEventRequestDTO> captor =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditService).logEvent(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a real GET under /patients/{id} is recorded against that patient")
    void recordsARealPatientRead() throws Exception {
        mockMvc.perform(get("/patients/{id}/allergies", PATIENT))
            .andExpect(status().isOk());

        assertThat(captureEmitted().getPatientId()).isEqualTo(PATIENT);
    }

    @Test
    @DisplayName("the {patientId} convention works through a real dispatch too")
    void recordsByConventionOnAnotherRoot() throws Exception {
        mockMvc.perform(get("/encounters/patient/{patientId}", PATIENT))
            .andExpect(status().isOk());

        assertThat(captureEmitted().getPatientId()).isEqualTo(PATIENT);
    }

    @Test
    @DisplayName("an {id} on a non-patient root is still not mistaken for a patient")
    void doesNotRecordForeignIds() throws Exception {
        mockMvc.perform(get("/appointments/{id}", UUID.randomUUID()))
            .andExpect(status().isOk());

        verify(auditService, never()).logEvent(any());
    }

    @Test
    @DisplayName("one chart open, many requests, one row")
    void collapsesARealChartOpen() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/patients/{id}/allergies", PATIENT)).andExpect(status().isOk());
        }
        mockMvc.perform(get("/encounters/patient/{patientId}", PATIENT))
            .andExpect(status().isOk());

        verify(auditService).logEvent(any());
    }
}
