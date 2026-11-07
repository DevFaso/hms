package com.example.hms.service;

import com.example.hms.repository.OrderReviewHoldRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.impl.OrderReviewAfterCommit;
import com.example.hms.service.impl.OrderReviewHoldServiceImpl;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F3: the controller reads the union of a caller's assignments, so a caller who is an administrator or a receptionist
 * at A and a nurse or a pharmacist at B gets through it acting at A. The real {@link RoleValidator} answers here, over
 * the caller's assignments, hospital by hospital.
 */
@DisplayName("The review list asks the role at the acting hospital, whatever the caller holds elsewhere")
class OrderReviewHoldListCallerTest {

    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();
    private final UUID caller = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final ActingScopeResolver scope = mock(ActingScopeResolver.class);
    private final OrderReviewHoldRepository holds = mock(OrderReviewHoldRepository.class);
    private OrderReviewHoldServiceImpl service;

    @BeforeEach
    void setUp() {
        RoleValidator roles = new RoleValidator(assignments, scope);
        service = new OrderReviewHoldServiceImpl(Clock.systemUTC(), holds, mock(com.example.hms.repository.PrescriptionRepository.class),
            mock(com.example.hms.repository.TransfusionRequestRepository.class), mock(com.example.hms.repository.TransfusionAdministrationRepository.class),
            mock(PatientChartAccess.class), roles, mock(com.example.hms.service.pharmacy.PrescriptionControlledResolver.class),
            mock(PrescriberReauthenticationService.class), mock(PrescriptionService.class), mock(TransfusionService.class),
            mock(InBasketService.class), mock(AuditEventLogService.class), mock(OrderReviewAfterCommit.class),
            mock(com.example.hms.repository.DeathRecordRepository.class), mock(com.example.hms.repository.DeathDisputeRepository.class),
            mock(com.example.hms.repository.MedicationAdministrationRecordRepository.class));
        when(holds.findByPatientIdAndHospitalIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void callerHolds(Map<UUID, String> assignedRoles, UUID actingAt) {
        org.mockito.Mockito.doAnswer(call -> {
            Collection<String> wanted = call.getArgument(2);
            String held = assignedRoles.get(call.<UUID>getArgument(1));
            return caller.equals(call.getArgument(0)) && held != null && wanted.contains(held);
        }).when(assignments).existsActiveByUserAndHospitalAndAnyRoleCode(any(), any(), any());
        ActingScope.Pinned pinned = new ActingScope.Pinned(actingAt, ActingScope.Source.HEADER);
        when(scope.current()).thenReturn(pinned);
        Set<SimpleGrantedAuthority> union = Set.copyOf(assignedRoles.values().stream().map(SimpleGrantedAuthority::new).toList());
        com.example.hms.model.User principal = new com.example.hms.model.User();
        principal.setId(caller);
        var token = new UsernamePasswordAuthenticationToken(principal, "n/a", union);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @Test
    @DisplayName("an administrator at A who is a nurse at B, acting at A: refused")
    void anAdministratorAtA() {
        callerHolds(Map.of(hospitalA, "ROLE_HOSPITAL_ADMIN", hospitalB, "ROLE_NURSE"), hospitalA);
        assertThatThrownBy(() -> service.forPatient(patient)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a receptionist at A who is a pharmacist at B, acting at A: refused")
    void aReceptionistAtA() {
        callerHolds(Map.of(hospitalA, "ROLE_RECEPTIONIST", hospitalB, "ROLE_PHARMACIST"), hospitalA);
        assertThatThrownBy(() -> service.forPatient(patient)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("the same callers, acting at B where they are clinical: allowed")
    void theSameCallerAtB() {
        callerHolds(Map.of(hospitalA, "ROLE_HOSPITAL_ADMIN", hospitalB, "ROLE_NURSE"), hospitalB);
        assertThatCode(() -> service.forPatient(patient)).doesNotThrowAnyException();
        callerHolds(Map.of(hospitalA, "ROLE_RECEPTIONIST", hospitalB, "ROLE_PHARMACIST"), hospitalB);
        assertThatCode(() -> service.forPatient(patient)).doesNotThrowAnyException();
    }
}
