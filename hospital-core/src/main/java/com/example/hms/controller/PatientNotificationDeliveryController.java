package com.example.hms.controller;

import com.example.hms.controller.support.QueryParameterValues;
import com.example.hms.enums.NotificationDeliveryStatus;
import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.notification.PatientNotificationDeliveryDTO;
import com.example.hms.payload.dto.notification.PatientNotificationDeliveryPageDTO;
import com.example.hms.service.notification.PatientNotificationDeliveryAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import com.example.hms.payload.dto.notification.PatientNotificationRetryDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Patient notification deliveries (V312, scorecard K3): the hospital administrators' list of SMS and
 * email sends to patients, failed ones first, with a manual retry. Hospital-scoped: the caller's
 * pinned hospital only.
 */
@RestController
@Tag(name = "Patient notification deliveries",
    description = "Appointment and result-available SMS / email sends to patients, their status and retry")
@RequiredArgsConstructor
public class PatientNotificationDeliveryController {

    static final String ADMIN_ROLES = "hasAnyRole('HOSPITAL_ADMIN', 'SUPER_ADMIN')";

    /** Every delivery status, and ALL. */
    static final Set<String> STATUS_CODES = Stream.concat(
        Arrays.stream(NotificationDeliveryStatus.values()).map(Enum::name), Stream.of("ALL"))
        .collect(Collectors.toUnmodifiableSet());

    private final PatientNotificationDeliveryAdminService service;

    @GetMapping("/patient-notification-deliveries")
    @PreAuthorize(ADMIN_ROLES)
    @Operation(summary = "List patient notification sends",
        description = "One page of the hospital's sends in one status (FAILED by default) or ALL, newest first, "
            + "with counts per status. Never the message, the number or the address.")
    @ApiResponse(responseCode = "200", description = "Page retrieved")
    @ApiResponse(responseCode = "400", description = "Unknown status")
    public ResponseEntity<ApiResponseWrapper<PatientNotificationDeliveryPageDTO>> search(
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size) {
        String code = QueryParameterValues.oneOf("status", status, STATUS_CODES);
        return ResponseEntity.ok(ApiResponseWrapper.success(service.search(code, page, size)));
    }

    @PostMapping("/patient-notification-deliveries/{id}/retry")
    @PreAuthorize(ADMIN_ROLES)
    @Operation(summary = "Retry a failed patient notification",
        description = "Puts a FAILED send back in the queue with a fresh attempt budget. Refused for any other "
            + "status, so a sent message is never sent again from here.")
    @ApiResponse(responseCode = "200", description = "Requeued")
    @ApiResponse(responseCode = "400", description = "Not FAILED")
    @ApiResponse(responseCode = "404", description = "No such send in this hospital")
    public ResponseEntity<ApiResponseWrapper<PatientNotificationDeliveryDTO>> retry(@PathVariable UUID id,
        @Valid @RequestBody PatientNotificationRetryDTO request) {
        return ResponseEntity.ok(ApiResponseWrapper.success(service.retry(id, request.expectedManualRetries())));
    }
}
