package com.example.hms.controller;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.LabResultRequestDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.service.LabResultService;
import com.example.hms.utility.Hl7v2MessageBuilder;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedObservation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Inbound HL7v2 adapter endpoint.
 * Accepts raw HL7v2 text messages from analyzers/middleware and parses
 * ORU^R01 observation segments into {@code LabResult} records.
 *
 * <p>In production this endpoint would be secured with network-level controls
 * (IP allowlist, mTLS) and a dedicated service/LLP listener. Here it is
 * exposed as a REST endpoint secured by RBAC for integration testing.
 */
@Slf4j
@RestController
@RequestMapping("/lab/hl7")
@Tag(name = "HL7v2 Inbound Adapter", description = "Accepts inbound HL7v2 ORU^R01 messages from lab analyzers")
@RequiredArgsConstructor
public class Hl7InboundController {

    private final Hl7v2MessageBuilder hl7v2MessageBuilder;
    private final LabResultService labResultService;

    /**
     * POST /lab/hl7/inbound
     * Accepts a raw HL7v2 message body (Content-Type: text/plain or text/hl7-v2).
     * Parses the first OBX segment and creates a {@code LabResult} record.
     *
     * <p>The caller must supply {@code X-Lab-Order-Id} and {@code X-Assignment-Id}
     * headers because HL7v2 PIDs in this environment are UUIDs, not MRNs, and
     * the order linkage must be explicit.
     */
    @PostMapping(value = "/inbound",
                 consumes = {MediaType.TEXT_PLAIN_VALUE, "text/hl7-v2", MediaType.APPLICATION_OCTET_STREAM_VALUE})
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'LAB_SCIENTIST', 'LAB_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "HL7v2 ORU^R01 Inbound",
               description = "Parses an inbound HL7v2 ORU^R01 message and creates a LabResult. " +
                             "Requires X-Lab-Order-Id (UUID) and X-Assignment-Id (UUID) request headers.")
    @ApiResponse(responseCode = "201", description = "LabResult created from HL7 message")
    @ApiResponse(responseCode = "400", description = "Unparseable HL7v2 message")
    public ResponseEntity<ApiResponseWrapper<LabResultResponseDTO>> inbound(
        @RequestBody String hl7Message,
        @RequestHeader("X-Lab-Order-Id")    UUID labOrderId,
        @RequestHeader("X-Assignment-Id")   UUID assignmentId,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        ParsedObservation obs = hl7v2MessageBuilder.parseOruR01(hl7Message);
        if (obs == null) {
            throw new com.example.hms.exception.BusinessException(
                "Unable to parse HL7v2 message. Ensure the message is a valid ORU^R01.");
        }

        log.info("Inbound HL7v2 ORU^R01: testCode={}, value={}, unit={}, flag={}",
            obs.testCode(), obs.resultValue(), obs.resultUnit(), obs.abnormalFlag());

        LabResultRequestDTO dto = LabResultRequestDTO.builder()
            .labOrderId(labOrderId)
            .assignmentId(assignmentId)
            .patientId(resolvePatientId(obs.patientId()))
            .resultValue(obs.resultValue())
            .resultUnit(obs.resultUnit())
            .resultDate(obs.resultDate() != null ? obs.resultDate() : LocalDateTime.now())
            .notes("Imported via HL7v2 ORU^R01 inbound adapter.")
            .build();

        LabResultResponseDTO created = labResultService.createLabResult(dto, locale);
        return ResponseEntity.status(201).body(ApiResponseWrapper.success(created));
    }

    /** HL7v2 PID may contain a UUID string or an MRN. Parse if it looks like a UUID. */
    private UUID resolvePatientId(String rawPid) {
        if (rawPid == null || rawPid.isBlank()) return null;
        try {
            return UUID.fromString(rawPid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
