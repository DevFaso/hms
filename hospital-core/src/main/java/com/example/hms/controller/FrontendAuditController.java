package com.example.hms.controller;

import com.example.hms.payload.dto.FrontendAuditEventRequestDTO;
import com.example.hms.service.FrontendAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.core.Authentication;
import com.example.hms.controller.support.ControllerAuthUtils;
import java.util.UUID;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/frontend-audit")
@RequiredArgsConstructor
@Tag(name = "Frontend Audit", description = "Capture client-side audit breadcrumbs.")
public class FrontendAuditController {

    private final FrontendAuditService frontendAuditService;
    private final ControllerAuthUtils authUtils;

    @Operation(
        summary = "Record a frontend audit event",
        description = "Allows the SPA to emit lightweight audit events during sensitive workflows.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponse(responseCode = "202", description = "Event accepted", content = @Content)
    @PostMapping
    public ResponseEntity<Void> recordEvent(
        @Valid @RequestBody FrontendAuditEventRequestDTO request,
        HttpServletRequest httpRequest,
        @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedIps,
        @RequestHeader(value = "User-Agent", required = false) String userAgent,
        Authentication authentication
    ) {
        // QA D701 sibling: the actor and the address are the server's to state,
        // never the payload's. A body-supplied "actor" let any caller attribute a
        // breadcrumb to someone else; the authenticated principal is the actor.
        request.setActor(authUtils.resolveUserId(authentication).map(UUID::toString).orElse(null));
        request.setIpAddress(null);
        String ipAddress = resolveIpAddress(forwardedIps, httpRequest);
        frontendAuditService.recordEvent(request, ipAddress, userAgent);
        return ResponseEntity.accepted().build();
    }

    private String resolveIpAddress(String forwardedIps, HttpServletRequest request) {
        if (forwardedIps != null && !forwardedIps.isBlank()) {
            return forwardedIps.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
