package com.example.hms.security.audit;

import com.example.hms.controller.LabOrderController;
import com.example.hms.controller.LabResultController;
import com.example.hms.controller.SuperAdminLabOrderController;
import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.AuditEventType;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.LabOrderRequestDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.LabResultRequestDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminLabOrderCreateRequestDTO;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.security.tenant.ActingScopeTestSupport;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.CriticalValueNotificationService;
import com.example.hms.service.LabOrderService;
import com.example.hms.service.LabResultService;
import com.example.hms.service.SuperAdminLabOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D429: a lab order or result created through the API was audited as a
 * bare DATA_CREATE with no record id and no patient, so the patient's
 * audit trail never showed it. Each create handler (clinician order,
 * super-admin order, result entry) runs for real here, then the write
 * audit records its row.
 */
@DisplayName("Lab create write audit (D429)")
class LabWriteAuditTest {

    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();

    private AuditEventLogService auditService;
    private WriteAuditInterceptor interceptor;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditEventLogService.class);
        interceptor = new WriteAuditInterceptor(providerOf(auditService),
            providerOf(new ControllerAuthUtils(ActingScopeTestSupport.resolver())),
            providerOf(mock(UserRoleHospitalAssignmentRepository.class)));
        ReflectionTestUtils.setField(interceptor, "enabled", true);
        CustomUserDetails principal = new CustomUserDetails(
            UUID.randomUUID(), "dr.kone", "x", true, List.of(new SimpleGrantedAuthority("ROLE_DOCTOR")));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    private void bindRequest(String pattern) {
        request = new MockHttpServletRequest("POST", pattern);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private AuditEventRequestDTO recordedAfter(Object controller, Method method) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(201);
        interceptor.afterCompletion(request, response, new HandlerMethod(controller, method), null);
        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditService).logEvent(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a clinician's lab order is LAB_ORDER_CREATED, naming the order and the patient")
    void clinicianOrder() throws Exception {
        bindRequest("/lab-orders");
        LabOrderService service = mock(LabOrderService.class);
        when(service.createLabOrder(any(), any())).thenReturn(
            LabOrderResponseDTO.builder().id(RECORD.toString()).patientId(PATIENT.toString()).build());
        LabOrderController controller = new LabOrderController(service, mock(MessageSource.class));
        controller.createLabOrder(new LabOrderRequestDTO(), Locale.ENGLISH);

        AuditEventRequestDTO row = recordedAfter(controller, LabOrderController.class.getMethod(
            "createLabOrder", LabOrderRequestDTO.class, Locale.class));
        assertThat(row.getEventType()).isEqualTo(AuditEventType.LAB_ORDER_CREATED);
        assertThat(row.getPatientId()).isEqualTo(PATIENT);
        assertThat(row.getResourceId()).isEqualTo(RECORD.toString());
        assertThat(row.getEntityType()).isEqualTo("LAB_ORDER");
    }

    @Test
    @DisplayName("a super admin's lab order is recorded the same way")
    void superAdminOrder() throws Exception {
        bindRequest("/super-admin/lab-orders");
        SuperAdminLabOrderService service = mock(SuperAdminLabOrderService.class);
        when(service.createLabOrder(any(), any())).thenReturn(
            LabOrderResponseDTO.builder().id(RECORD.toString()).patientId(PATIENT.toString()).build());
        SuperAdminLabOrderController controller = new SuperAdminLabOrderController(service);
        controller.createLabOrder(new SuperAdminLabOrderCreateRequestDTO(), Locale.ENGLISH);

        AuditEventRequestDTO row = recordedAfter(controller, SuperAdminLabOrderController.class.getMethod(
            "createLabOrder", SuperAdminLabOrderCreateRequestDTO.class, Locale.class));
        assertThat(row.getEventType()).isEqualTo(AuditEventType.LAB_ORDER_CREATED);
        assertThat(row.getPatientId()).isEqualTo(PATIENT);
        assertThat(row.getResourceId()).isEqualTo(RECORD.toString());
    }

    @Test
    @DisplayName("an entered lab result is a lab-result event naming the result and the patient")
    void labResult() throws Exception {
        bindRequest("/lab-results");
        LabResultService service = mock(LabResultService.class);
        when(service.createLabResult(any(), any())).thenReturn(
            LabResultResponseDTO.builder().id(RECORD.toString()).patientId(PATIENT.toString()).build());
        LabResultController controller = new LabResultController(service,
            mock(CriticalValueNotificationService.class), mock(MessageSource.class));
        controller.createLabResult(new LabResultRequestDTO(), Locale.ENGLISH);

        AuditEventRequestDTO row = recordedAfter(controller, LabResultController.class.getMethod(
            "createLabResult", LabResultRequestDTO.class, Locale.class));
        assertThat(row.getEventType()).isEqualTo(AuditEventType.LAB_RESULT_CREATED);
        assertThat(row.getPatientId()).isEqualTo(PATIENT);
        assertThat(row.getResourceId()).isEqualTo(RECORD.toString());
        assertThat(row.getEntityType()).isEqualTo("LAB_RESULT");
    }

    @Test
    @DisplayName("outside a request the handler's annotation call is a no-op")
    void noRequestBound() {
        RequestContextHolder.resetRequestAttributes();
        WriteAuditInterceptor.describeWrite(RECORD.toString(), PATIENT.toString());
        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
    }
}
