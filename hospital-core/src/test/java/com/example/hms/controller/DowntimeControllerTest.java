package com.example.hms.controller;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.security.DowntimeStateService;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * QA CX-001 (round 33): a platform-wide read-only mode says why. The refusal is
 * on the server too, so nothing but the confirmed portal path can turn every
 * hospital read-only in silence; lifting the mode needs no reason; both are audited.
 */
@ExtendWith(MockitoExtension.class)
class DowntimeControllerTest {

    private static final Authentication ACTOR = new TestingAuthenticationToken("super.alice", "x");

    @Mock private DowntimeStateService downtimeStateService;
    @Mock private AuditEventLogService auditEventLogService;

    @InjectMocks
    private DowntimeController controller;

    private static DowntimeController.DowntimeToggleRequest request(boolean readOnly, String message) {
        return DowntimeController.DowntimeToggleRequest.builder().readOnly(readOnly).message(message).build();
    }

    @Test
    @DisplayName("activating with no message, or a blank one, is refused on its field and changes nothing")
    void activatingSaysWhy() {
        for (String blank : new String[] {null, "", "   "}) {
            DowntimeController.DowntimeToggleRequest body = request(true, blank);
            assertThatThrownBy(() -> controller.toggle(body, ACTOR))
                .isInstanceOfSatisfying(FieldBusinessException.class,
                    refused -> assertThat(refused.getFieldErrors()).containsOnlyKeys("message"));
        }
        verify(downtimeStateService, never()).setReadOnly(anyBoolean(), any(), any());
        verifyNoInteractions(auditEventLogService);
    }

    @Test
    @DisplayName("activating with a message goes through and the audit row carries it")
    void activatingIsAudited() {
        when(downtimeStateService.setReadOnly(true, "Maintenance", "super.alice"))
            .thenReturn(new DowntimeStateService.DowntimeSnapshot(true, "Maintenance", Instant.now()));

        controller.toggle(request(true, "Maintenance"), ACTOR);

        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventDescription()).contains("ACTIVATED").contains("Maintenance");
        assertThat(audit.getValue().getUserName()).isEqualTo("super.alice");
    }

    @Test
    @DisplayName("lifting the mode needs no reason and is audited")
    void liftingNeedsNoReason() {
        when(downtimeStateService.setReadOnly(false, null, "super.alice"))
            .thenReturn(new DowntimeStateService.DowntimeSnapshot(false, null, null));

        controller.toggle(request(false, null), ACTOR);

        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventDescription()).contains("deactivated");
    }
}
