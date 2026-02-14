package com.example.hms.controller;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.mapper.AuditEventLogMapper;
import com.example.hms.model.AuditEventLog;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.service.AuditEventLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/audit-logs")
@Validated
@RequiredArgsConstructor
@Tag(name = "Audit Logs", description = "Endpoints for managing system audit logs and tracking events.")
public class AuditEventLogController {

    private final AuditEventLogService auditService;
    private final AuditEventLogRepository auditRepository;
    private final AuditEventLogMapper auditMapper;

    @PostMapping
    @Operation(summary = "Log an Audit Event", description = "Creates a new audit log entry and returns it.")
    @ApiResponse(responseCode = "200", description = "Audit event logged successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "404", description = "User not found")
    public ResponseEntity<AuditEventLogResponseDTO> logEvent(
        @Valid @RequestBody AuditEventRequestDTO requestDTO) {
        AuditEventLogResponseDTO response = auditService.logEvent(requestDTO);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Operation(summary = "Get All Audit Logs", description = "Retrieve all audit logs with pagination.")
    @ApiResponse(responseCode = "200", description = "Audit logs retrieved successfully")
    public ResponseEntity<Page<AuditEventLogResponseDTO>> getAllAuditLogs(
        @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<AuditEventLog> page = auditRepository.findAll(pageable);
        return ResponseEntity.ok(page.map(auditMapper::toDto));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get Audit Logs by User", description = "Retrieve audit logs for a specific user based on their UUID.")
    @ApiResponse(responseCode = "200", description = "Audit logs retrieved successfully")
    public ResponseEntity<Page<AuditEventLogResponseDTO>> getLogsByUser(
        @Parameter(description = "UUID of the user", required = true)
        @PathVariable UUID userId,
        @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(auditService.getAuditLogsByUser(userId, pageable));
    }

    @GetMapping("/event-type-status")
    @Operation(summary = "Get Audit Logs by Event Type (with optional status)", description = "Retrieve audit logs filtered by event type and optionally by status.")
    @ApiResponse(responseCode = "200", description = "Audit logs retrieved successfully")
    @ApiResponse(responseCode = "400", description = "Invalid audit event type or status")
    public ResponseEntity<Page<AuditEventLogResponseDTO>> getLogsByEventTypeAndStatus(
        @RequestParam String eventType,
        @RequestParam(required = false) String status,
        @PageableDefault(size = 20) Pageable pageable) {

        try {
            AuditEventType parsedType = AuditEventType.valueOf(eventType.trim().toUpperCase());

            AuditStatus parsedStatus = null;
            if (status != null && !status.isBlank()) {
                parsedStatus = AuditStatus.valueOf(status.trim().toUpperCase());
            }

            Page<AuditEventLog> page = (parsedStatus == null)
                ? auditRepository.findByEventType(parsedType, pageable)
                : auditRepository.findByEventTypeAndStatus(parsedType, parsedStatus, pageable);

            return ResponseEntity.ok(page.map(auditMapper::toDto));

        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid eventType or status provided.");
        }
    }

    @GetMapping("/target")
    @Operation(summary = "Get Audit Logs by Target Resource", description = "Retrieve audit logs based on the affected entity type and resource ID.")
    @ApiResponse(responseCode = "200", description = "Audit logs retrieved successfully")
    public ResponseEntity<Page<AuditEventLogResponseDTO>> getLogsByTarget(
        @RequestParam String entityType,
        @RequestParam String resourceId,
        @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(
            auditRepository.findByEntityTypeIgnoreCaseAndResourceId(entityType, resourceId, pageable)
                .map(auditMapper::toDto)
        );
    }
}
