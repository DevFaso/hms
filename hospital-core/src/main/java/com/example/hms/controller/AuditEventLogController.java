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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
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
    @Operation(summary = "Get All Audit Logs", description = "Retrieve all audit logs with pagination and optional date range filtering.")
    @ApiResponse(responseCode = "200", description = "Audit logs retrieved successfully")
    public ResponseEntity<Page<AuditEventLogResponseDTO>> getAllAuditLogs(
        @PageableDefault(size = 20) Pageable pageable,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(required = false) String eventType
    ) {
        LocalDateTime from = fromDate != null ? fromDate.atStartOfDay() : null;
        LocalDateTime to = toDate != null ? toDate.atTime(LocalTime.MAX) : null;

        Page<AuditEventLog> page;
        if (from != null && to != null && eventType != null && !eventType.isBlank()) {
            try {
                AuditEventType parsedType = AuditEventType.valueOf(eventType.trim().toUpperCase());
                page = auditRepository.findByEventTypeAndEventTimestampBetween(parsedType, from, to, pageable);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid eventType provided.");
            }
        } else if (from != null && to != null) {
            page = auditRepository.findByEventTimestampBetween(from, to, pageable);
        } else {
            page = auditRepository.findAll(pageable);
        }
        return ResponseEntity.ok(page.map(auditMapper::toDto));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_ADMIN')")
    @Operation(summary = "Export Audit Logs as CSV", description = "Export audit logs for a date range as a CSV file.")
    @ApiResponse(responseCode = "200", description = "CSV export generated successfully")
    public ResponseEntity<byte[]> exportAuditLogs(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate
    ) {
        LocalDateTime from = fromDate.atStartOfDay();
        LocalDateTime to = toDate.atTime(LocalTime.MAX);

        List<AuditEventLog> logs = auditRepository.findAllByEventTimestampBetween(from, to);
        List<AuditEventLogResponseDTO> dtos = logs.stream().map(auditMapper::toDto).toList();

        StringBuilder csv = new StringBuilder();
        csv.append("Timestamp,User,Hospital,Role,Event Type,Description,Entity Type,Resource,Status,IP Address\n");
        for (AuditEventLogResponseDTO dto : dtos) {
            csv.append(escapeCsv(dto.getEventTimestamp() != null ? dto.getEventTimestamp().toString() : "")).append(',');
            csv.append(escapeCsv(dto.getUserName())).append(',');
            csv.append(escapeCsv(dto.getHospitalName())).append(',');
            csv.append(escapeCsv(dto.getRoleName())).append(',');
            csv.append(escapeCsv(dto.getEventType())).append(',');
            csv.append(escapeCsv(dto.getEventDescription())).append(',');
            csv.append(escapeCsv(dto.getEntityType())).append(',');
            csv.append(escapeCsv(dto.getResourceName())).append(',');
            csv.append(escapeCsv(dto.getStatus())).append(',');
            csv.append(escapeCsv(dto.getIpAddress())).append('\n');
        }

        byte[] content = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String filename = "audit-logs-" + fromDate + "-to-" + toDate + ".csv";

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("text/csv"))
            .contentLength(content.length)
            .body(content);
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
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

    @GetMapping("/event-types-summary")
    @Operation(summary = "Get event type counts", description = "Retrieve a summary of audit event counts grouped by event type.")
    @ApiResponse(responseCode = "200", description = "Event type summary retrieved successfully")
    public ResponseEntity<List<Map<String, Object>>> getEventTypesSummary() {
        List<Map<String, Object>> summary = auditRepository.countByEventType().stream()
            .map(row -> Map.<String, Object>of(
                "eventType", row[0] != null ? row[0].toString() : "UNKNOWN",
                "count", row[1]
            ))
            .toList();
        return ResponseEntity.ok(summary);
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
