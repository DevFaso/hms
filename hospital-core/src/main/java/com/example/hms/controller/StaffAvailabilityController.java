package com.example.hms.controller;

import com.example.hms.payload.dto.StaffAvailabilityRequestDTO;
import com.example.hms.payload.dto.StaffAvailabilityResponseDTO;
import com.example.hms.service.StaffAvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/availability")
@RequiredArgsConstructor
@Tag(name = "Staff Availability", description = "Manage staff schedules, day offs, and availability checks for appointment scheduling.")
public class StaffAvailabilityController {

    private final StaffAvailabilityService service;

    @Operation(
            summary = "Create availability for a staff member",
            description = "Define an availability time slot for a given staff member. Prevents overlap if implemented in service."
    )
    @ApiResponse(responseCode = "200", description = "Availability created successfully.",
            content = @Content(schema = @Schema(implementation = StaffAvailabilityResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Invalid input or business rule violation.",
            content = @Content)
    @ApiResponse(responseCode = "404", description = "Staff member not found.", content = @Content)
    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN')")
    public ResponseEntity<StaffAvailabilityResponseDTO> create(
            @Parameter(description = "Availability request payload", required = true)
            @RequestBody @Valid StaffAvailabilityRequestDTO dto,
            @Parameter(hidden = true)
            Locale locale
    ) {
        return ResponseEntity.ok(service.create(dto, locale));
    }

    @Operation(
            summary = "Check if staff is available at a specific datetime",
            description = "Returns true if the staff has availability at the specified datetime. Checks for overlapping schedules or day offs."
    )
    @ApiResponse(responseCode = "200", description = "Returns true if available, false otherwise.")
    @ApiResponse(responseCode = "400", description = "Invalid request parameters.", content = @Content)
    @ApiResponse(responseCode = "404", description = "Staff not found.", content = @Content)
    @GetMapping("/check")
    public ResponseEntity<Boolean> checkAvailability(
            @Parameter(description = "UUID of the staff member", required = true)
            @RequestParam UUID staffId,

            @Parameter(description = "Datetime to check (e.g., 2025-06-15T10:00:00)", required = true)
            @RequestParam LocalDateTime datetime
    ) {
        return ResponseEntity.ok(service.isStaffAvailable(staffId, datetime));
    }
}

