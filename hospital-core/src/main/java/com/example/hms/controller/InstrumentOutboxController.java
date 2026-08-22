package com.example.hms.controller;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.InstrumentOutboxPageDTO;
import com.example.hms.payload.dto.InstrumentOutboxResponseDTO;
import com.example.hms.payload.dto.InstrumentOutboxTransportDTO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@Tag(name = "Lab Instrument Integration", description = "Outbound HL7v2 outbox monitoring and inbound ORU^R01 result ingestion")
@RequiredArgsConstructor
public class InstrumentOutboxController {

    /**
     * Matches the filter-chain rule in SecurityConfig for GET
     * /lab-instrument-outbox/** — until 2026-08-22 this annotation omitted
     * LAB_DIRECTOR and QUALITY_MANAGER, so the filter chain admitted two roles
     * the method then 403'd.
     */
    private static final String READ_ROLES =
        "hasAnyRole('LAB_TECHNICIAN', 'LAB_SCIENTIST', 'LAB_MANAGER', 'LAB_DIRECTOR', "
        + "'QUALITY_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')";

    /** Requeueing changes what goes out on the wire — kept to the lab's supervisory roles. */
    private static final String RETRY_ROLES =
        "hasAnyRole('LAB_MANAGER', 'LAB_DIRECTOR', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')";

    private final InstrumentOutboxService instrumentOutboxService;
    private final Hl7v2MessageBuilder hl7v2MessageBuilder;

    // ── Outbox monitoring ─────────────────────────────────────────────────────

    /**
     * GET /lab-instrument-outbox
     * One page of the hospital-scoped queue, payload elided, with status counts.
     */
    @GetMapping("/lab-instrument-outbox")
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "Search Outbox Queue",
               description = "Returns a page of outbound HL7 messages for the caller's hospital, "
                   + "optionally filtered by status (PENDING, ACK, ERROR), newest first. "
                   + "Payloads are elided; fetch a single message for the full HL7 text.")
    @ApiResponse(responseCode = "200", description = "Page retrieved")
    public ResponseEntity<ApiResponseWrapper<InstrumentOutboxPageDTO>> searchQueue(
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        return ResponseEntity.ok(ApiResponseWrapper.success(
            instrumentOutboxService.search(status, page, size)));
    }

    /**
     * GET /lab-instrument-outbox/transport
     * Read-only outbound-transport configuration, so "nothing is being sent"
     * is diagnosable from the portal rather than from a server DEBUG log.
     */
    @GetMapping("/lab-instrument-outbox/transport")
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "Get Outbound Transport Status",
               description = "Returns whether the outbound MLLP sender is enabled and its retry configuration.")
    @ApiResponse(responseCode = "200", description = "Transport status retrieved")
    public ResponseEntity<ApiResponseWrapper<InstrumentOutboxTransportDTO>> getTransportStatus(
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        return ResponseEntity.ok(ApiResponseWrapper.success(
            instrumentOutboxService.getTransportStatus()));
    }

    /**
     * GET /lab-instrument-outbox/orders/{id}
     * Returns ALL outbound messages for the given lab order. Until 2026-08-22
     * this filtered to PENDING — the one monitoring read that existed silently
     * dropped exactly the ERROR rows an operator would be hunting for.
     */
    @GetMapping("/lab-instrument-outbox/orders/{id}")
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "Get Outbox Messages For Order",
               description = "Returns every OML^O21 and ORU^R01 outbound message for the given lab order, "
                   + "whatever its delivery status.")
    @ApiResponse(responseCode = "200", description = "Messages retrieved")
    public ResponseEntity<ApiResponseWrapper<List<InstrumentOutboxResponseDTO>>> getMessagesForOrder(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        List<InstrumentOutboxResponseDTO> messages =
            instrumentOutboxService.getMessagesByLabOrder(id);
        return ResponseEntity.ok(ApiResponseWrapper.success(messages));
    }

    /**
     * GET /lab-instrument-outbox/{id}
     * One message including its full HL7 payload.
     */
    @GetMapping("/lab-instrument-outbox/{id}")
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "Get Outbox Message",
               description = "Returns one outbound message including its full HL7 payload.")
    @ApiResponse(responseCode = "200", description = "Message retrieved")
    @ApiResponse(responseCode = "404", description = "Message not found")
    public ResponseEntity<ApiResponseWrapper<InstrumentOutboxResponseDTO>> getMessage(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        return ResponseEntity.ok(ApiResponseWrapper.success(
            instrumentOutboxService.getMessage(id)));
    }

    /**
     * POST /lab-instrument-outbox/{id}/retry
     * Requeue a permanently failed message. ERROR is otherwise an absorbing
     * state — the sweep only selects PENDING, so a parked row stays parked even
     * after the analyser comes back.
     */
    @PostMapping("/lab-instrument-outbox/{id}/retry")
    @PreAuthorize(RETRY_ROLES)
    @Operation(summary = "Retry Failed Outbox Message",
               description = "Puts a message in ERROR back into the dispatch queue with a fresh attempt budget. "
                   + "Refused for any other status.")
    @ApiResponse(responseCode = "200", description = "Message requeued")
    @ApiResponse(responseCode = "400", description = "Message is not in ERROR")
    @ApiResponse(responseCode = "404", description = "Message not found")
    public ResponseEntity<ApiResponseWrapper<InstrumentOutboxResponseDTO>> retryMessage(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        return ResponseEntity.ok(ApiResponseWrapper.success(
            instrumentOutboxService.retry(id)));
    }

    // ── HL7v2 inbound ─────────────────────────────────────────────────────────

    /**
     * POST /lab-instrument-outbox/hl7/parse
     * Accepts a raw HL7v2 message (text/plain or application/hl7-v2) from a connected analyzer
     * and returns the parsed observation fields for verification. This endpoint serves as the
     * integration gateway for inbound ORU^R01 and OUL result messages.
     *
     * <p>The parsed observation is returned as JSON. A downstream workflow (or a follow-up
     * {@code POST /lab-results}) uses these fields to create the corresponding {@code LabResult}.
     */
    @PostMapping(
        path = "/lab-instrument-outbox/hl7/parse",
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
