package com.example.hms.security.audit;

import com.example.hms.enums.AuditEventType;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.service.AuditEventLogService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PatientAccessAuditInterceptor")
class PatientAccessAuditInterceptorTest {

    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();

    private AuditEventLogService auditService;
    private PatientAccessAuditInterceptor interceptor;

    /** Handler methods to point the interceptor at. Never invoked. */
    @SuppressWarnings("unused")
    static class Handlers {
        public void byConvention() { }

        @PatientAccessAudited("subjectId")
        public void named() { }

        @PatientAccessAudited(skip = true)
        public void optedOut() { }
    }

    @BeforeEach
    void setUp() {
        auditService = mock(AuditEventLogService.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<AuditEventLogService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(auditService);

        interceptor = new PatientAccessAuditInterceptor(provider, 30, 1000);
        ReflectionTestUtils.setField(interceptor, "enabled", true);

        authenticateAs("dr.kabore", "ROLE_DOCTOR");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username, String... authorities) {
        CustomUserDetails principal = new CustomUserDetails(
            DOCTOR, username, "x", true,
            java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private HandlerMethod handler(String method) throws NoSuchMethodException {
        return new HandlerMethod(new Handlers(), Handlers.class.getMethod(method));
    }

    private MockHttpServletRequest get(String pattern, Map<String, String> pathVariables) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/irrelevant");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, pathVariables);
        return request;
    }

    private MockHttpServletResponse ok() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        return response;
    }

    private AuditEventRequestDTO captureEmitted() {
        ArgumentCaptor<AuditEventRequestDTO> captor =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditService).logEvent(captor.capture());
        return captor.getValue();
    }

    // ── What must be recorded ────────────────────────────────────────────

    @Test
    @DisplayName("QA D187: a read at a pinned hospital carries the actor's assignment there and the bare role code")
    void recordsTheHospitalOfTheRead() throws Exception {
        UUID hospitalId = UUID.randomUUID();
        com.example.hms.repository.UserRoleHospitalAssignmentRepository repository =
            mock(com.example.hms.repository.UserRoleHospitalAssignmentRepository.class);
        com.example.hms.model.Role role = new com.example.hms.model.Role();
        role.setCode("ROLE_DOCTOR");
        role.setName("Médecin");
        com.example.hms.model.UserRoleHospitalAssignment assignment = new com.example.hms.model.UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setRole(role);
        when(repository.findFirstByUser_IdAndHospital_IdAndActiveTrue(DOCTOR, hospitalId))
            .thenReturn(java.util.Optional.of(assignment));
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditEventLogService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(auditService);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.example.hms.repository.UserRoleHospitalAssignmentRepository> repoProvider = mock(ObjectProvider.class);
        when(repoProvider.getIfAvailable()).thenReturn(repository);
        PatientAccessAuditInterceptor scoped = new PatientAccessAuditInterceptor(provider, repoProvider, 30, 1000);
        ReflectionTestUtils.setField(scoped, "enabled", true);
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder()
                .principalUserId(DOCTOR).activeHospitalId(hospitalId).headerOverridden(true).build());
        try {
            scoped.afterCompletion(
                get("/encounters/patient/{patientId}", Map.of("patientId", PATIENT.toString())),
                ok(), handler("byConvention"), null);
        } finally {
            com.example.hms.security.context.HospitalContextHolder.clear();
        }

        AuditEventRequestDTO emitted = captureEmitted();
        assertThat(emitted.getAssignmentId()).isEqualTo(assignment.getId());
        assertThat(emitted.getRoleName()).isEqualTo("DOCTOR");
    }

    @Test
    @DisplayName("QA D840: every chart read under break-the-glass is counted on the session, past the PATIENT_ACCESS dedupe")
    void countsEveryReadUnderABreakGlassSession() throws Exception {
        UUID hospitalB = UUID.randomUUID();
        com.example.hms.service.BreakGlassService breakGlass = mock(com.example.hms.service.BreakGlassService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditEventLogService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(auditService);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.example.hms.service.BreakGlassService> breakGlassProvider = mock(ObjectProvider.class);
        when(breakGlassProvider.getIfAvailable()).thenReturn(breakGlass);
        PatientAccessAuditInterceptor counting =
            new PatientAccessAuditInterceptor(provider, null, breakGlassProvider, 30, 1000);
        ReflectionTestUtils.setField(counting, "enabled", true);
        // DOCTOR_B, pinned to hospital B, opens the chart three times.
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder()
                .principalUserId(DOCTOR).activeHospitalId(hospitalB)
                .permittedHospitalIds(java.util.Set.of(hospitalB)).build());
        try {
            for (int read = 0; read < 3; read++) {
                counting.afterCompletion(get("/patients/{id}", Map.of("id", PATIENT.toString())),
                    ok(), handler("byConvention"), null);
            }
        } finally {
            com.example.hms.security.context.HospitalContextHolder.clear();
        }

        verify(breakGlass, times(3))
            .consumeReadIfLive(PATIENT, DOCTOR, hospitalB, "GET /patients/{id}");
        // The ordinary access row is still deduped to one.
        verify(auditService, times(1)).logEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("QA D840: a break-the-glass counting failure never costs the ordinary access row")
    void aCountingFailureStillRecordsTheAccess() throws Exception {
        com.example.hms.service.BreakGlassService breakGlass = mock(com.example.hms.service.BreakGlassService.class);
        when(breakGlass.consumeReadIfLive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(new IllegalStateException("db down"));
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditEventLogService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(auditService);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.example.hms.service.BreakGlassService> breakGlassProvider = mock(ObjectProvider.class);
        when(breakGlassProvider.getIfAvailable()).thenReturn(breakGlass);
        PatientAccessAuditInterceptor counting =
            new PatientAccessAuditInterceptor(provider, null, breakGlassProvider, 30, 1000);
        ReflectionTestUtils.setField(counting, "enabled", true);

        counting.afterCompletion(get("/patients/{id}", Map.of("id", PATIENT.toString())),
            ok(), handler("byConvention"), null);

        assertThat(captureEmitted().getEventType()).isEqualTo(AuditEventType.PATIENT_ACCESS);
    }

    @Test
    @DisplayName("QA D840: a failed read counts nothing on a break-the-glass session")
    void aRefusedReadCountsNothing() throws Exception {
        com.example.hms.service.BreakGlassService breakGlass = mock(com.example.hms.service.BreakGlassService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditEventLogService> provider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.example.hms.service.BreakGlassService> breakGlassProvider = mock(ObjectProvider.class);
        org.mockito.Mockito.lenient().when(breakGlassProvider.getIfAvailable()).thenReturn(breakGlass);
        PatientAccessAuditInterceptor counting =
            new PatientAccessAuditInterceptor(provider, null, breakGlassProvider, 30, 1000);
        ReflectionTestUtils.setField(counting, "enabled", true);
        MockHttpServletResponse forbidden = new MockHttpServletResponse();
        forbidden.setStatus(403);

        counting.afterCompletion(get("/patients/{id}", Map.of("id", PATIENT.toString())),
            forbidden, handler("byConvention"), null);

        org.mockito.Mockito.verifyNoInteractions(breakGlass);
    }

    @Test
    @DisplayName("records a chart read, attributed to the right patient and clinician")
    void recordsAPatientScopedRead() throws Exception {
        interceptor.afterCompletion(
            get("/encounters/patient/{patientId}", Map.of("patientId", PATIENT.toString())),
            ok(), handler("byConvention"), null);

        AuditEventRequestDTO emitted = captureEmitted();
        assertThat(emitted.getEventType()).isEqualTo(AuditEventType.PATIENT_ACCESS);
        assertThat(emitted.getPatientId()).isEqualTo(PATIENT);
        assertThat(emitted.getUserId()).isEqualTo(DOCTOR);
        assertThat(emitted.getUserName()).isEqualTo("dr.kabore");
        assertThat(emitted.getRoleName()).isEqualTo("DOCTOR");
        // "PATIENT" is what folds into TREATMENT_ACCESS on the patient's page.
        // Any other value files the row under the wrong heading or drops it.
        assertThat(emitted.getEntityType()).isEqualTo("PATIENT");
    }

    @Test
    @DisplayName("treats {id} as the patient only under /patients/")
    void resolvesIdOnlyUnderThePatientsRoot() throws Exception {
        interceptor.afterCompletion(
            get("/patients/{id}/allergies", Map.of("id", PATIENT.toString())),
            ok(), handler("byConvention"), null);

        assertThat(captureEmitted().getPatientId()).isEqualTo(PATIENT);
    }

    @Test
    @DisplayName("an annotation names the parameter when the convention cannot find it")
    void honoursTheNamedParameter() throws Exception {
        interceptor.afterCompletion(
            get("/reports/{subjectId}", Map.of("subjectId", PATIENT.toString())),
            ok(), handler("named"), null);

        assertThat(captureEmitted().getPatientId()).isEqualTo(PATIENT);
    }

    @Test
    @DisplayName("the named parameter may arrive as a query string")
    void honoursTheNamedRequestParameter() throws Exception {
        MockHttpServletRequest request = get("/reports", Map.of());
        request.setParameter("subjectId", PATIENT.toString());

        interceptor.afterCompletion(request, ok(), handler("named"), null);

        assertThat(captureEmitted().getPatientId()).isEqualTo(PATIENT);
    }

    // ── What must NOT be recorded ────────────────────────────────────────

    @Test
    @DisplayName("an {id} outside /patients/ is somebody else's id, not a patient")
    void doesNotGuessAtForeignIds() throws Exception {
        // Filing an appointment id against a patient would put a stranger's
        // name on someone's disclosure page. A missing entry is bad; a wrong
        // one is worse, because the patient cannot tell it is wrong.
        interceptor.afterCompletion(
            get("/appointments/{id}", Map.of("id", UUID.randomUUID().toString())),
            ok(), handler("byConvention"), null);

        verify(auditService, never()).logEvent(any());
    }

    @Test
    @DisplayName("a failed read disclosed nothing")
    void doesNotRecordFailures() throws Exception {
        MockHttpServletResponse forbidden = new MockHttpServletResponse();
        forbidden.setStatus(403);

        interceptor.afterCompletion(
            get("/patients/{id}", Map.of("id", PATIENT.toString())),
            forbidden, handler("byConvention"), null);

        verify(auditService, never()).logEvent(any());
    }

    @Test
    @DisplayName("a handler that threw disclosed nothing either")
    void doesNotRecordWhenTheHandlerThrew() throws Exception {
        interceptor.afterCompletion(
            get("/patients/{id}", Map.of("id", PATIENT.toString())),
            ok(), handler("byConvention"), new IllegalStateException("boom"));

        verify(auditService, never()).logEvent(any());
    }

    @Test
    @DisplayName("writes are not views")
    void doesNotRecordWrites() throws Exception {
        MockHttpServletRequest post = get("/patients/{id}", Map.of("id", PATIENT.toString()));
        post.setMethod("POST");

        interceptor.afterCompletion(post, ok(), handler("byConvention"), null);

        verify(auditService, never()).logEvent(any());
    }

    @Test
    @DisplayName("a patient reading their own record is not a disclosure")
    void doesNotRecordSelfAccess() throws Exception {
        // Without this, opening the disclosure page would append to the
        // disclosure page, and every patient's history would be mostly
        // themselves.
        authenticateAs("awa.traore", "ROLE_PATIENT");

        interceptor.afterCompletion(
            get("/patients/{id}", Map.of("id", PATIENT.toString())),
            ok(), handler("byConvention"), null);

        verify(auditService, never()).logEvent(any());
    }

    @Test
    @DisplayName("an endpoint can opt out")
    void honoursTheOptOut() throws Exception {
        interceptor.afterCompletion(
            get("/patients/{id}", Map.of("id", PATIENT.toString())),
            ok(), handler("optedOut"), null);

        verify(auditService, never()).logEvent(any());
    }

    @Test
    @DisplayName("an unauthenticated request records nothing")
    void doesNotRecordWithoutAPrincipal() throws Exception {
        SecurityContextHolder.clearContext();

        interceptor.afterCompletion(
            get("/patients/{id}", Map.of("id", PATIENT.toString())),
            ok(), handler("byConvention"), null);

        verify(auditService, never()).logEvent(any());
    }

    @Test
    @DisplayName("a non-uuid path segment is not a patient id")
    void ignoresNonUuidIds() throws Exception {
        // Several of these paths take a code, an MRN or a slug.
        interceptor.afterCompletion(
            get("/patients/{id}/summary", Map.of("id", "MRN-00421")),
            ok(), handler("byConvention"), null);

        verify(auditService, never()).logEvent(any());
    }

    @Test
    @DisplayName("the flag switches it off completely")
    void respectsTheDisableFlag() throws Exception {
        ReflectionTestUtils.setField(interceptor, "enabled", false);

        interceptor.afterCompletion(
            get("/patients/{id}", Map.of("id", PATIENT.toString())),
            ok(), handler("byConvention"), null);

        verify(auditService, never()).logEvent(any());
    }

    // ── It must never affect the response ────────────────────────────────

    @Test
    @DisplayName("an audit failure does not escape into the request")
    void swallowsAuditFailures() throws Exception {
        // The response is already written by afterCompletion. An audit problem
        // becoming the caller's problem would mean this change could break
        // reads it was added to observe.
        when(auditService.logEvent(any())).thenThrow(new RuntimeException("audit down"));

        MockHttpServletRequest request =
            get("/patients/{id}", Map.of("id", PATIENT.toString()));

        interceptor.afterCompletion(request, ok(), handler("byConvention"), null);

        verify(auditService).logEvent(any());
    }

    @Test
    @DisplayName("a missing audit service is tolerated, for the test slices that have none")
    void toleratesAnAbsentAuditService() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditEventLogService> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        PatientAccessAuditInterceptor bare =
            new PatientAccessAuditInterceptor(empty, 30, 1000);
        ReflectionTestUtils.setField(bare, "enabled", true);

        bare.afterCompletion(
            get("/patients/{id}", Map.of("id", PATIENT.toString())),
            ok(), handler("byConvention"), null);

        verify(auditService, never()).logEvent(any());
    }

    // ── Coalescing, end to end through the interceptor ───────────────────

    @Test
    @DisplayName("one chart open produces one row, not one per endpoint it loads")
    void collapsesTheRequestsOfOneChartOpen() throws Exception {
        List<String> chartOpen = List.of(
            "/patients/{id}", "/patients/{id}/allergies", "/patients/{id}/diagnoses",
            "/patients/{id}/vitals", "/patients/{id}/lab-results", "/patients/{id}/notes");

        for (String pattern : chartOpen) {
            interceptor.afterCompletion(
                get(pattern, Map.of("id", PATIENT.toString())),
                ok(), handler("byConvention"), null);
        }

        verify(auditService).logEvent(any());
    }
}
