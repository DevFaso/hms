package com.example.hms.security.audit;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.service.AuditEventLogService;
import tools.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D4264: the patient's « Qui a consulté mon dossier » lists the reads that
 * returned data, and no other. Driven through a real dispatch, with the
 * interceptor and {@link PatientReadAnswerProbe} wired as the application
 * wires them: a read that answers with nothing writes no row, whatever shape
 * the empty answer has; a read that answers with a record writes one.
 */
@DisplayName("D4264: only a read that returned data is a consultation of the record")
class PatientAccessReturnedDataDispatchTest {

    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();

    @RestController
    static class ChartController {
        @GetMapping("/admissions/patient/{patientId}")
        ResponseEntity<List<String>> noAdmissions(@PathVariable UUID patientId) {
            return ResponseEntity.ok(List.of());
        }

        @GetMapping("/billing-invoices/patient/{patientId}")
        ResponseEntity<Page<String>> noInvoices(@PathVariable UUID patientId) {
            return ResponseEntity.ok(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        }

        @GetMapping("/admissions/patient/{patientId}/current")
        ResponseEntity<Void> noCurrentStay(@PathVariable UUID patientId) {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/pharmacy/dispense/patient/{patientId}")
        ResponseEntity<ApiResponseWrapper<Page<String>>> noDispensing(@PathVariable UUID patientId) {
            return ResponseEntity.ok(ApiResponseWrapper.<Page<String>>builder()
                .success(true).data(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0)).build());
        }

        @GetMapping("/patients/{patientId}/primary-care/current")
        Optional<String> noPrimaryCare(@PathVariable UUID patientId) {
            return Optional.empty();
        }

        @GetMapping("/patients/{patientId}/settings")
        Map<String, String> noSettings(@PathVariable UUID patientId) {
            return Map.of();
        }

        @GetMapping("/patients/{id}/allergies")
        ResponseEntity<List<String>> allergies(@PathVariable UUID id) {
            return ResponseEntity.ok(List.of("Pénicilline"));
        }

        @GetMapping("/patients/{id}")
        ResponseEntity<Map<String, String>> patient(@PathVariable UUID id) {
            return ResponseEntity.ok(Map.of("id", id.toString()));
        }

        @GetMapping("/patients/{patientId}/visit-count")
        ResponseEntity<Integer> count(@PathVariable UUID patientId) {
            return ResponseEntity.ok(0);
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

        PatientAccessAuditInterceptor interceptor = new PatientAccessAuditInterceptor(provider, 30, 1000);
        ReflectionTestUtils.setField(interceptor, "enabled", true);

        mockMvc = MockMvcBuilders.standaloneSetup(new ChartController())
            .addInterceptors(interceptor)
            .setControllerAdvice(new PatientReadAnswerProbe())
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

    @Test
    @DisplayName("an empty list, an empty page, no content, an empty wrapped page, an empty optional and an empty map write no row")
    void aReadThatReturnedNothingIsNotAConsultation() throws Exception {
        mockMvc.perform(get("/admissions/patient/{patientId}", PATIENT)).andExpect(status().isOk());
        mockMvc.perform(get("/billing-invoices/patient/{patientId}", PATIENT)).andExpect(status().isOk());
        mockMvc.perform(get("/admissions/patient/{patientId}/current", PATIENT)).andExpect(status().isNoContent());
        mockMvc.perform(get("/pharmacy/dispense/patient/{patientId}", PATIENT)).andExpect(status().isOk());
        mockMvc.perform(get("/patients/{patientId}/primary-care/current", PATIENT)).andExpect(status().isOk());
        mockMvc.perform(get("/patients/{patientId}/settings", PATIENT)).andExpect(status().isOk());

        verify(auditService, never()).logEvent(any());
    }

    @Test
    @DisplayName("a list with a row is a consultation, recorded against the patient")
    void aReadThatReturnedRowsIsRecorded() throws Exception {
        mockMvc.perform(get("/patients/{id}/allergies", PATIENT)).andExpect(status().isOk());

        assertThat(emitted().getPatientId()).isEqualTo(PATIENT);
    }

    @Test
    @DisplayName("a record is a consultation; empty reads before it do not use up its row")
    void anEmptyReadDoesNotConsumeTheRowOfTheNextRealRead() throws Exception {
        mockMvc.perform(get("/admissions/patient/{patientId}", PATIENT)).andExpect(status().isOk());
        mockMvc.perform(get("/patients/{id}", PATIENT)).andExpect(status().isOk());

        assertThat(emitted().getPatientId()).isEqualTo(PATIENT);
    }

    @Test
    @DisplayName("a count is an answer, zero included")
    void aCountIsAnAnswer() throws Exception {
        mockMvc.perform(get("/patients/{patientId}/visit-count", PATIENT)).andExpect(status().isOk());

        assertThat(emitted().getPatientId()).isEqualTo(PATIENT);
    }

    @Test
    @DisplayName("the probe: what counts as nothing, and what does not")
    void whatCountsAsNothing() {
        assertThat(PatientReadAnswerProbe.returnsNothing(null)).isTrue();
        assertThat(PatientReadAnswerProbe.returnsNothing(new String[0])).isTrue();
        assertThat(PatientReadAnswerProbe.returnsNothing(JsonNodeFactory.instance.arrayNode())).isTrue();
        assertThat(PatientReadAnswerProbe.returnsNothing(JsonNodeFactory.instance.nullNode())).isTrue();
        assertThat(PatientReadAnswerProbe.returnsNothing(Optional.of(List.of()))).isTrue();
        assertThat(PatientReadAnswerProbe.returnsNothing(ApiResponseWrapper.builder().success(true).build())).isTrue();

        assertThat(PatientReadAnswerProbe.returnsNothing("")).isFalse();
        assertThat(PatientReadAnswerProbe.returnsNothing(0)).isFalse();
        assertThat(PatientReadAnswerProbe.returnsNothing(List.of("x"))).isFalse();
        assertThat(PatientReadAnswerProbe.returnsNothing(new PageImpl<>(List.of("x")))).isFalse();
        assertThat(PatientReadAnswerProbe.returnsNothing(new PageImpl<>(List.of(), PageRequest.of(5, 20), 30)))
            .as("a page past the end that still counts rows").isFalse();
        assertThat(PatientReadAnswerProbe.returnsNothing(JsonNodeFactory.instance.objectNode().put("id", "x"))).isFalse();
    }

    private AuditEventRequestDTO emitted() {
        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditService).logEvent(captor.capture());
        return captor.getValue();
    }
}
