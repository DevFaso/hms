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
@RequestMapping("/lab/hl7/adapter")
@Tag(name = "HL7v2 Inbound Adapter", description = "Accepts inbound HL7v2 ORU^R01 messages from lab analyzers")
@RequiredArgsConstructor
public class Hl7InboundController {

    private final Hl7v2MessageBuilder hl7v2MessageBuilder;
    private final LabResultService labResultService;

    /**
     * POST /lab/hl7/inbound
     * Accepts a raw HL7v2 message body (Content-Type: text/plain or text/hl7-v2)
     * and creates a {@code LabResult} record from the FIRST observation.
     *
     * <p>Single-result by design: this REST adapter returns one DTO and takes
     * its order linkage from headers, so it deliberately keeps first-OBX
     * semantics even though the parser now returns every OBX. Multi-OBX
     * fan-out lives on the MLLP path ({@code MllpInboundLabService}), which
     * persists one row per observation.
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

        // The MSH line, for the replay guard. Parsed here because the
        // observation records carry OBX/OBR only — and defensively, because
        // Hl7MessageInspector is written for the MLLP transport, where a
        // malformed frame is answered with an AR rather than an HTTP status:
        // it throws MllpProtocolException (which no @ExceptionHandler maps,
        // so it would surface as a 500) for a body that does not start with
        // MSH, and a StringIndexOutOfBoundsException for a body of exactly
        // "MSH". Running it ahead of the parse guard therefore turned this
        // endpoint's documented 400 into a 500. A body we cannot read an MSH
        // from simply has no replay identity; the guard below still rejects
        // it as unparseable, which is the 400 the contract promises.
        com.example.hms.hl7.mllp.Hl7MessageHeader header = readHeaderOrNull(hl7Message);
        java.util.List<ParsedObservation> observations = hl7v2MessageBuilder.parseOruR01(hl7Message);
        if (observations == null || observations.isEmpty()) {
            throw new com.example.hms.exception.BusinessException(
                "Unable to parse HL7v2 message. Ensure the message is a valid ORU^R01.");
        }
        ParsedObservation obs = observations.get(0);

        log.info("Inbound HL7v2 ORU^R01: testCode={}, value={}, unit={}, flag={}",
            obs.testCode(), obs.resultValue(), obs.resultUnit(), obs.abnormalFlag());

        LabResultRequestDTO dto = LabResultRequestDTO.builder()
            .labOrderId(labOrderId)
            .assignmentId(assignmentId)
            .patientId(resolvePatientId(obs.patientId()))
            // Truncated to the columns they land in, exactly as the MLLP
            // path does with trimToNull(..., 255). Persisting them verbatim
            // meant a long analyte code or facility name failed an ingest
            // that worked before these fields were carried at all.
            .testCode(trimToColumn(obs.testCode()))
            // MSH-3/4/10: what makes a retransmission recognisable as one.
            .sourceSendingApplication(trimToColumn(header == null ? null : header.sendingApplication()))
            .sourceSendingFacility(trimToColumn(header == null ? null : header.sendingFacility()))
            .sourceMessageControlId(trimToColumn(header == null ? null : header.messageControlId()))
            .resultValue(obs.resultValue())
            .resultUnit(obs.resultUnit())
            .resultDate(obs.resultDate() != null ? obs.resultDate() : LocalDateTime.now())
            .notes("Imported via HL7v2 ORU^R01 inbound adapter.")
            .build();

        LabResultResponseDTO created = labResultService.createIngestedLabResult(dto, locale);
        return ResponseEntity.status(201).body(ApiResponseWrapper.success(created));
    }

    /** The longest any of these source columns is. */
    private static final int SOURCE_COLUMN_LENGTH = 255;

    /**
     * The MSH line, or null when this body has none.
     *
     * <p>{@code Hl7MessageInspector} belongs to the MLLP transport and
     * signals a malformed frame by throwing; here a malformed body is just a
     * body with no replay identity, and the parse guard that follows answers
     * it with the documented 400.
     */
    private com.example.hms.hl7.mllp.Hl7MessageHeader readHeaderOrNull(String hl7Message) {
        try {
            return com.example.hms.hl7.mllp.Hl7MessageInspector.parseHeader(hl7Message);
        } catch (RuntimeException notReadable) {
            log.debug("Inbound HL7v2 body carries no readable MSH; no replay identity: {}",
                notReadable.getMessage());
            return null;
        }
    }

    /** Trim to the column, as the MLLP path does; blank becomes null. */
    private static String trimToColumn(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= SOURCE_COLUMN_LENGTH
            ? trimmed : trimmed.substring(0, SOURCE_COLUMN_LENGTH);
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
