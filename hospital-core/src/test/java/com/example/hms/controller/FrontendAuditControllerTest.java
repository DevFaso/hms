package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.FrontendAuditEventRequestDTO;
import com.example.hms.service.FrontendAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D701 sibling: a client breadcrumb is attributed to the caller, never to whoever the body names. */
@DisplayName("Frontend audit: actor and address come from the server")
class FrontendAuditControllerTest {

    private final FrontendAuditService service = mock(FrontendAuditService.class);
    private final ControllerAuthUtils authUtils = mock(ControllerAuthUtils.class);
    private final FrontendAuditController controller = new FrontendAuditController(service, authUtils);

    @Test
    void bodyActorAndIpOverrideAreReplacedByThePrincipal() {
        UUID caller = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(caller));
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getRemoteAddr()).thenReturn("10.0.0.7");
        FrontendAuditEventRequestDTO body = FrontendAuditEventRequestDTO.builder()
            .type("SILENT_403").actor("someone-else").ipAddress("203.0.113.9").build();

        controller.recordEvent(body, http, null, "ua", auth);

        ArgumentCaptor<FrontendAuditEventRequestDTO> sent = ArgumentCaptor.forClass(FrontendAuditEventRequestDTO.class);
        verify(service).recordEvent(sent.capture(), eq("10.0.0.7"), eq("ua"));
        assertThat(sent.getValue().getActor()).isEqualTo(caller.toString());
        assertThat(sent.getValue().getIpAddress()).isNull();
    }

    @Test
    void forwardedForIsStillHonoured() {
        Authentication auth = mock(Authentication.class);
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.empty());
        FrontendAuditEventRequestDTO body = FrontendAuditEventRequestDTO.builder().type("X").actor("forged").build();

        controller.recordEvent(body, mock(HttpServletRequest.class), "198.51.100.4, 10.0.0.1", null, auth);

        verify(service).recordEvent(body, "198.51.100.4", null);
        assertThat(body.getActor()).isNull();
    }
}
