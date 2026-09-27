package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.procedure.ProcedureOrderRequestDTO;
import com.example.hms.payload.dto.procedure.ProcedureOrderResponseDTO;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.ProcedureOrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /procedure-orders} resolves the ordering clinician through
 * {@link ControllerAuthUtils#resolveUserId} — the {@code appUserId} claim of a
 * Keycloak token as well as a password principal. The old parser read the
 * username as a UUID and threw "Unsupported authentication principal type" on
 * a JWT, so an order could never be placed over SSO.
 */
@DisplayName("POST /procedure-orders: who is ordering")
class ProcedureOrderControllerCallerTest {

    private final ProcedureOrderService service = mock(ProcedureOrderService.class);
    private final ProcedureOrderController controller = new ProcedureOrderController(
        service, new ControllerAuthUtils(mock(UserRoleHospitalAssignmentRepository.class)));

    @Test
    @DisplayName("a Keycloak clinician is resolved from appUserId, not from the token subject")
    void keycloakCaller() {
        UUID appUserId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256")
            .claim("sub", "keycloak-subject").claim("appUserId", appUserId.toString()).build();
        ProcedureOrderRequestDTO request = new ProcedureOrderRequestDTO();
        when(service.createProcedureOrder(request, appUserId)).thenReturn(new ProcedureOrderResponseDTO());

        assertThat(controller.createProcedureOrder(request,
            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_DOCTOR")))).getStatusCode().value())
            .isEqualTo(201);
        verify(service).createProcedureOrder(request, appUserId);
    }

    @Test
    @DisplayName("a principal with no resolvable user id is refused before the service")
    void unresolvableCaller() {
        ProcedureOrderRequestDTO request = new ProcedureOrderRequestDTO();
        var anonymous = new UsernamePasswordAuthenticationToken("not-a-uuid", null, List.of());
        assertThatThrownBy(() -> controller.createProcedureOrder(request, anonymous))
            .isInstanceOf(IllegalArgumentException.class);
        verify(service, never()).createProcedureOrder(any(), any());
    }
}
