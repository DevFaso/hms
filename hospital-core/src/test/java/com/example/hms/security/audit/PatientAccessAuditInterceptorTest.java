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
