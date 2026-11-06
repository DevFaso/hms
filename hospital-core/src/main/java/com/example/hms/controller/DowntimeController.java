package com.example.hms.controller;

import com.example.hms.security.audit.WriteAudited;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.security.DowntimeStateService;
import com.example.hms.service.AuditEventLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Downtime read-only mode (P3 #23a).
 *
 * <p>GET /downtime/status is the persisted banner feed — unlike the
 * emergency broadcast (fire-and-forget STOMP that post-send logins never
 * see), this survives login and refresh. The PUT lives under
 * /super-admin/** so intent is explicit; both paths ride
 * anyRequest().authenticated() at the filter chain, so the annotations
 * are the authoritative gate. The PUT path is allowlisted in
 * ReadOnlyModeFilter — a mode you cannot turn off is an outage.
 */
@Slf4j
@RestController
@WriteAudited(skip = true, reason = "service emits CONFIGURATION_CHANGED with the mode and reason")
@RequiredArgsConstructor
@Tag(name = "Downtime", description = "Platform read-only continuity mode")
public class DowntimeController {

    private final DowntimeStateService downtimeStateService;
    private final AuditEventLogService auditEventLogService;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DowntimeToggleRequest {
        /** QA round 29 sweep: required - an empty body silently turned read-only mode off. */
        @jakarta.validation.constraints.NotNull
        private Boolean readOnly;
        @Size(max = 500)
        private String message;
    }

    public record DowntimeStatusResponse(boolean readOnly, String message, Instant activatedAt) {}

    @GetMapping("/downtime/status")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Current downtime state (feeds the portal banner)",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<DowntimeStatusResponse> status() {
        DowntimeStateService.DowntimeSnapshot snapshot = downtimeStateService.snapshot();
        return ResponseEntity.ok(new DowntimeStatusResponse(
            snapshot.readOnly(), snapshot.message(), snapshot.activatedAt()));
    }

    @PutMapping("/super-admin/downtime")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Toggle platform read-only mode",
        description = "Takes effect immediately on this instance; within 30s on others.",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<DowntimeStatusResponse> toggle(
        @Valid @RequestBody DowntimeToggleRequest request,
        Authentication auth
    ) {
        // QA CX-001: turning the whole platform read-only says why: the message is
        // what every signed-in user reads on the banner. Lifting it needs none.
        if (Boolean.TRUE.equals(request.getReadOnly())
                && (request.getMessage() == null || request.getMessage().isBlank())) {
            throw new FieldBusinessException("message", "downtime.message.required");
        }
        String actor = auth != null ? auth.getName() : null;
        DowntimeStateService.DowntimeSnapshot snapshot =
            downtimeStateService.setReadOnly(request.getReadOnly(), request.getMessage(), actor);

        // Best effort, the house audit stance: the toggle must not fail
        // because the audit write did.
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userName(actor)
                .eventType(AuditEventType.CONFIGURATION_CHANGED)
                .eventDescription("Platform read-only mode "
                    + (Boolean.TRUE.equals(request.getReadOnly()) ? "ACTIVATED" : "deactivated")
                    + (request.getMessage() != null ? ": " + request.getMessage() : ""))
                .entityType("PLATFORM_DOWNTIME")
                .resourceId("1")
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException ex) {
            log.warn("Downtime toggle audit emit failed: {}", ex.getMessage());
        }
        return ResponseEntity.ok(new DowntimeStatusResponse(
            snapshot.readOnly(), snapshot.message(), snapshot.activatedAt()));
    }
}
