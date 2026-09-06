package com.example.hms.security.audit;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.AuditEventType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.service.AuditEventLogService;
import org.hibernate.LazyInitializationException;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private UserRoleHospitalAssignmentRepository assignmentRepository;
    private WriteAuditInterceptor interceptor;

    @SuppressWarnings("unused")
    static class Handlers {
        public void plain() {
            // handler bodies are irrelevant: only the annotations are read
        }

        @WriteAudited(entity = "BLOOD_UNIT", idVar = "unitId", patientIdVar = "subjectId")
        public void named() {
            // see plain()
        }

        @WriteAudited(skip = true, reason = "service emits its own event")
        public void optedOut() {
            // see plain()
        }
    }

    @SuppressWarnings("unused")
    @WriteAudited(skip = true, reason = "whole controller emits its own events")
    static class OptedOutController {
        public void anything() {
            // see Handlers.plain()
        }

        @WriteAudited
        public void butThisOne() {
            // see Handlers.plain()
        }
    }

    @BeforeEach
    void setUp() {
        auditService = mock(AuditEventLogService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditEventLogService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(auditService);
        assignmentRepository = mock(UserRoleHospitalAssignmentRepository.class);
        interceptor = new WriteAuditInterceptor(provider, providerOf(new ControllerAuthUtils(mock(UserRoleHospitalAssignmentRepository.class))),
            providerOf(assignmentRepository));
        ReflectionTestUtils.setField(interceptor, "enabled", true);
        authenticate();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
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
        HospitalContextHolder.clear();
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

    @Test
    @DisplayName("a Keycloak JwtAuthenticationToken is attributed through its uid claim, not dropped")
    void recordsOidcPrincipals() throws Exception {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
            .claim("uid", NURSE.toString()).claim("preferred_username", "nurse.awa").subject("nurse.awa").build();
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_NURSE"))));

        interceptor.afterCompletion(request("POST", "/labor/episodes", Map.of()), ok(201),
            handler(Handlers.class, "plain"), null);

        AuditEventRequestDTO row = emitted();
        assertThat(row.getUserId()).isEqualTo(NURSE);
        assertThat(row.getUserName()).isEqualTo("nurse.awa");
        assertThat(row.getRoleName()).isEqualTo("NURSE");
    }

    @Test
    @DisplayName("a POST whose route ends in a read verb is a read, not DATA_CREATE")
    void readOnlyPostsAreNotRecorded() throws Exception {
        for (String route : List.of("/appointments/search", "/departments/filter", "/empi/candidates",
                "/eligibility/check", "/maternal-history/{id}/calculate-risk")) {
            interceptor.afterCompletion(request("POST", route, Map.of()), ok(200), handler(Handlers.class, "plain"), null);
        }
        verify(auditService, never()).logEvent(org.mockito.ArgumentMatchers.any());
        assertThat(WriteAuditInterceptor.isReadOnlyPost("POST", "/transfusions/requests")).isFalse();
        assertThat(WriteAuditInterceptor.isReadOnlyPost("PUT", "/x/search")).isFalse();
    }

    @Test
    @DisplayName("a hospital-scoped request carries the actor's assignment id; the hospital itself is never touched here")
    void anchorsTheRowToTheActiveHospitalAssignment() throws Exception {
        UUID hospitalId = UUID.randomUUID();
        // afterCompletion runs with no persistence context, so the assignment's
        // LAZY hospital is a dead proxy: reading its name threw and lost the
        // whole row (dev, 2026-09-05). The audit service derives the name from
        // assignmentId under its own transaction; this interceptor must not.
        Hospital deadProxy = mock(Hospital.class);
        when(deadProxy.getName()).thenThrow(new LazyInitializationException("could not initialize proxy - no session"));
        Role role = new Role();
        role.setName("ROLE_MIDWIFE");
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(deadProxy);
        assignment.setRole(role);
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(NURSE, hospitalId))
            .thenReturn(Optional.of(assignment));
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(NURSE).activeHospitalId(hospitalId).headerOverridden(true).build());

        interceptor.afterCompletion(request("POST", "/labor/episodes", Map.of()), ok(201),
            handler(Handlers.class, "plain"), null);

        AuditEventRequestDTO row = emitted();
        assertThat(row.getAssignmentId()).isEqualTo(assignment.getId());
        // Same bare form as the fallback and the read-side interceptor.
        assertThat(row.getRoleName()).isEqualTo("MIDWIFE");
        assertThat(row.getHospitalName()).isNull();
        verify(deadProxy, never()).getName();
    }

    @Test
    @DisplayName("without an active hospital the row is global: no assignment, no hospital name")
    void globalViewRowsCarryNoAssignment() throws Exception {
        interceptor.afterCompletion(request("POST", "/labor/episodes", Map.of()), ok(201),
            handler(Handlers.class, "plain"), null);
        AuditEventRequestDTO row = emitted();
        assertThat(row.getAssignmentId()).isNull();
        assertThat(row.getHospitalName()).isNull();
        verify(assignmentRepository, never()).findFirstByUser_IdAndHospital_IdAndActiveTrue(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
