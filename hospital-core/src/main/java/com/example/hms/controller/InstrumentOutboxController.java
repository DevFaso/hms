package com.example.hms.controller;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.InstrumentOutboxResponseDTO;
import com.example.hms.service.InstrumentOutboxService;
import com.example.hms.utility.Hl7v2MessageBuilder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@Tag(name = "Lab Instrument Integration", description = "Outbound HL7v2 outbox monitoring and inbound ORU^R01 result ingestion")
@RequiredArgsConstructor
public class InstrumentOutboxController {

    private final InstrumentOutboxService instrumentOutboxService;
    private final Hl7v2MessageBuilder hl7v2MessageBuilder;

    // ── Outbox monitoring ─────────────────────────────────────────────────────

    /**
     * GET /lab-instrument-outbox/orders/{id}
     * Returns pending outbound messages for the given lab order (for monitoring / retry dashboards).
     */
    @GetMapping("/lab-instrument-outbox/orders/{id}")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'LAB_SCIENTIST', 'LAB_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get Pending Outbox Messages",
               description = "Returns pending OML^O21 and ORU^R01 outbound messages for the given lab order.")
    @ApiResponse(responseCode = "200", description = "Messages retrieved")
    public ResponseEntity<ApiResponseWrapper<List<InstrumentOutboxResponseDTO>>> getPendingMessages(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        List<InstrumentOutboxResponseDTO> messages =
            instrumentOutboxService.getPendingMessagesByLabOrder(id);
        return ResponseEntity.ok(ApiResponseWrapper.success(messages));
    }

    // ── HL7v2 inbound ─────────────────────────────────────────────────────────

    /**
     * POST /lab/hl7/inbound
     * Accepts a raw HL7v2 message (text/plain or application/hl7-v2) from a connected analyzer
     * and returns the parsed observation fields for verification. This endpoint serves as the
     * integration gateway for inbound ORU^R01 and OUL result messages.
     *
     * <p>The parsed observation is returned as JSON. A downstream workflow (or a follow-up
     * {@code POST /lab-results}) uses these fields to create the corresponding {@code LabResult}.
     */
    @PostMapping(
        path = "/lab/hl7/inbound",
        consumes = {MediaType.TEXT_PLAIN_VALUE, "application/hl7-v2", MediaType.APPLICATION_OCTET_STREAM_VALUE},
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Ingest Inbound HL7v2 Message",
               description = "Accepts a raw HL7v2 ORU^R01 / OUL message from an analyzer interface engine. "
                   + "Parses the first OBX segment and returns a structured JSON representation for downstream processing.")
    @ApiResponse(responseCode = "200", description = "Message parsed successfully")
    @ApiResponse(responseCode = "422", description = "Message could not be parsed")
    public ResponseEntity<ApiResponseWrapper<ParsedObservationView>> ingestHl7Message(
        @RequestBody String hl7Message,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        Hl7v2MessageBuilder.ParsedObservation parsed = hl7v2MessageBuilder.parseOruR01(hl7Message);
        if (parsed == null) {
            throw new com.example.hms.exception.BusinessException(
                "Unable to parse the supplied HL7v2 message. Ensure it contains a valid MSH and OBX segment.");
        }
        return ResponseEntity.ok(ApiResponseWrapper.success(new ParsedObservationView(parsed)));
    }

    /** JSON-serialisable view of a parsed HL7v2 observation (wraps the record). */
    public record ParsedObservationView(
        String patientId,
        String testCode,
        String resultValue,
        String resultUnit,
        String abnormalFlag,
        java.time.LocalDateTime resultDate
    ) {
        ParsedObservationView(Hl7v2MessageBuilder.ParsedObservation obs) {
            this(obs.patientId(), obs.testCode(), obs.resultValue(),
                 obs.resultUnit(), obs.abnormalFlag(), obs.resultDate());
        }
    }
}
