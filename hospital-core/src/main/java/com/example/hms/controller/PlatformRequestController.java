package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.service.platform.PlatformRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * QA D4308: a hospital administrator's request to the platform, delivered to
 * the platform administrators' inbox.
 */
@RestController
@RequestMapping("/platform-requests")
@RequiredArgsConstructor
@Tag(name = "Platform requests", description = "A hospital administrator writes to the platform administrators")
@SecurityRequirement(name = "bearerAuth")
public class PlatformRequestController {

    private final PlatformRequestService service;
    private final ControllerAuthUtils authUtils;

    /** The request as typed: a short subject and the message. */
    public record PlatformRequestBody(String subject, String message) { }

    @PostMapping
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    @Operation(summary = "Send a request to the platform administrators (in-app inbox only)")
    public ResponseEntity<Map<String, UUID>> send(@RequestBody PlatformRequestBody body, Authentication auth) {
        UUID requesterId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new ResourceNotFoundException("error.user.notFound", auth == null ? "" : auth.getName()));
        UUID id = service.send(requesterId, body == null ? null : body.subject(), body == null ? null : body.message());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }
}
