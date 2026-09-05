package com.example.hms.security.audit;

import com.example.hms.enums.AuditEventType;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.service.AuditEventLogService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WriteAuditInterceptor")
class WriteAuditInterceptorTest {

    private static final UUID NURSE = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID RESOURCE = UUID.randomUUID();

    private AuditEventLogService auditService;
    private WriteAuditInterceptor interceptor;

    @SuppressWarnings("unused")
    static class Handlers {
        public void plain() { }

        @WriteAudited(entity = "BLOOD_UNIT", idVar = "unitId", patientIdVar = "subjectId")
        public void named() { }

        @WriteAudited(skip = true, reason = "service emits its own event")
        public void optedOut() { }
    }

    @SuppressWarnings("unused")
    @WriteAudited(skip = true, reason = "whole controller emits its own events")
    static class OptedOutController {
        public void anything() { }

        @WriteAudited
        public void butThisOne() { }
    }

    @BeforeEach
    void setUp() {
        auditService = mock(AuditEventLogService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditEventLogService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(auditService);
        interceptor = new WriteAuditInterceptor(provider);
        ReflectionTestUtils.setField(interceptor, "enabled", true);
        authenticate();
    }

    private static void authenticate() {
        CustomUserDetails principal = new CustomUserDetails(
            NURSE, "nurse.awa", "x", true, List.of(new SimpleGrantedAuthority("ROLE_NURSE")));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String method, String pattern, Map<String, String> vars) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, pattern);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, vars);
        request.setRemoteAddr("10.0.0.7");
        return request;
    }

    private static MockHttpServletResponse ok(int status) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        return response;
    }

    private static HandlerMethod handler(Class<?> type, String name) throws ReflectiveOperationException {
        return new HandlerMethod(type.getDeclaredConstructor().newInstance(), type.getDeclaredMethod(name));
    }

    private AuditEventRequestDTO emitted() {
        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditService).logEvent(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a successful POST is DATA_CREATE with the entity derived from the route and the patient from {patientId}")
    void recordsCreateByConvention() throws Exception {
        MockHttpServletRequest req = request("POST", "/transfusions/requests/{patientId}",
            Map.of("patientId", PATIENT.toString()));
        interceptor.afterCompletion(req, ok(201), handler(Handlers.class, "plain"), null);

        AuditEventRequestDTO row = emitted();
        assertThat(row.getEventType()).isEqualTo(AuditEventType.DATA_CREATE);
        assertThat(row.getEntityType()).isEqualTo("TRANSFUSION");
        assertThat(row.getPatientId()).isEqualTo(PATIENT);
        assertThat(row.getResourceId()).isNull();
        assertThat(row.getUserId()).isEqualTo(NURSE);
        assertThat(row.getUserName()).isEqualTo("nurse.awa");
        assertThat(row.getRoleName()).isEqualTo("NURSE");
        assertThat(row.getIpAddress()).isEqualTo("10.0.0.7");
        assertThat(row.getEventDescription()).isEqualTo("POST /transfusions/requests/{patientId}");
    }

    @Test
    @DisplayName("PUT/PATCH are DATA_UPDATE with the resource from {id}; DELETE is DATA_DELETE")
    void recordsUpdateAndDelete() throws Exception {
        interceptor.afterCompletion(request("PATCH", "/micro-cultures/{id}", Map.of("id", RESOURCE.toString())),
            ok(200), handler(Handlers.class, "plain"), null);
        AuditEventRequestDTO update = emitted();
        assertThat(update.getEventType()).isEqualTo(AuditEventType.DATA_UPDATE);
        assertThat(update.getEntityType()).isEqualTo("MICRO_CULTURE");
        assertThat(update.getResourceId()).isEqualTo(RESOURCE.toString());

        org.mockito.Mockito.reset(auditService);
        interceptor.afterCompletion(request("DELETE", "/advance-directives/{id}", Map.of("id", RESOURCE.toString())),
            ok(204), handler(Handlers.class, "plain"), null);
        assertThat(emitted().getEventType()).isEqualTo(AuditEventType.DATA_DELETE);
    }

    @Test
    @DisplayName("the annotation names the entity, the id variable and the patient variable")
    void honoursTheAnnotation() throws Exception {
        MockHttpServletRequest req = request("PUT", "/blood-bank/units/{unitId}/for/{subjectId}",
            Map.of("unitId", RESOURCE.toString(), "subjectId", PATIENT.toString()));
        interceptor.afterCompletion(req, ok(200), handler(Handlers.class, "named"), null);

        AuditEventRequestDTO row = emitted();
        assertThat(row.getEntityType()).isEqualTo("BLOOD_UNIT");
        assertThat(row.getResourceId()).isEqualTo(RESOURCE.toString());
        assertThat(row.getPatientId()).isEqualTo(PATIENT);
    }

    @Test
    @DisplayName("skip on the method or the class records nothing; a method annotation re-enables one handler")
    void honoursSkip() throws Exception {
        interceptor.afterCompletion(request("POST", "/x", Map.of()), ok(200), handler(Handlers.class, "optedOut"), null);
        interceptor.afterCompletion(request("POST", "/x", Map.of()), ok(200),
            handler(OptedOutController.class, "anything"), null);
        verify(auditService, never()).logEvent(org.mockito.ArgumentMatchers.any());

        interceptor.afterCompletion(request("POST", "/x", Map.of()), ok(200),
            handler(OptedOutController.class, "butThisOne"), null);
        assertThat(emitted().getEventDescription()).isEqualTo("POST /x");
    }

    @Test
    @DisplayName("GETs, failures, exceptions, unauthenticated callers and the kill switch record nothing")
    void recordsNothingWhenItShouldNot() throws Exception {
        HandlerMethod plain = handler(Handlers.class, "plain");
        interceptor.afterCompletion(request("GET", "/x", Map.of()), ok(200), plain, null);
        interceptor.afterCompletion(request("POST", "/x", Map.of()), ok(400), plain, null);
        interceptor.afterCompletion(request("POST", "/x", Map.of()), ok(500), plain, null);
        interceptor.afterCompletion(request("POST", "/x", Map.of()), ok(200), plain, new IllegalStateException("boom"));

        SecurityContextHolder.clearContext();
        interceptor.afterCompletion(request("POST", "/x", Map.of()), ok(200), plain, null);

        authenticate();
        ReflectionTestUtils.setField(interceptor, "enabled", false);
        interceptor.afterCompletion(request("POST", "/x", Map.of()), ok(200), plain, null);

        verify(auditService, never()).logEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("route → entity: namespaces skipped, kebab to snake, plural singularised")
    void derivesEntityTypes() {
        assertThat(WriteAuditInterceptor.entityTypeFor("/super-admin/webhook-endpoints/{id}", null)).isEqualTo("WEBHOOK_ENDPOINT");
        assertThat(WriteAuditInterceptor.entityTypeFor("/laboratories/{id}", null)).isEqualTo("LABORATORY");
        assertThat(WriteAuditInterceptor.entityTypeFor("/me/preferences", null)).isEqualTo("PREFERENCE");
        assertThat(WriteAuditInterceptor.entityTypeFor("/address/{id}", null)).isEqualTo("ADDRESS");
        assertThat(WriteAuditInterceptor.entityTypeFor("/", null)).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("an audit failure is logged, not thrown into the finished response")
    void swallowsAuditFailures() throws Exception {
        when(auditService.logEvent(org.mockito.ArgumentMatchers.any())).thenThrow(new RuntimeException("db down"));
        HandlerMethod plain = handler(Handlers.class, "plain");
        org.assertj.core.api.Assertions.assertThatCode(() ->
            interceptor.afterCompletion(request("POST", "/x", Map.of()), ok(200), plain, null))
            .doesNotThrowAnyException();
    }
}
