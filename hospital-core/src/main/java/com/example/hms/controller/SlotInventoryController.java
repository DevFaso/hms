package com.example.hms.controller;

import com.example.hms.payload.dto.scheduling.AppointmentSlotDTO;
import com.example.hms.payload.dto.scheduling.SlotBookingRequestDTO;
import com.example.hms.payload.dto.scheduling.SlotGenerationResultDTO;
import com.example.hms.service.scheduling.SlotInventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Slot inventory (P2 #11) — foundation pass.
 *
 * <p>Answers the question the scheduling model could not: "who is free, in this
 * department, for this kind of visit, over this window?" Patient-facing
 * self-scheduling and waitlist auto-offer (P3 #22) are separate surfaces built
 * on this one, deliberately not included here.
 */
@RestController
@RequestMapping(value = "/slots", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Slot Inventory", description = "Generate and search bookable appointment slots")
@SecurityRequirement(name = "bearerAuth")
public class SlotInventoryController {

    private static final String READ_ROLES =
        "hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE',"
            + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    /** Generating and blocking reshape the rota, so they are administrative. */
    private static final String ADMIN_ROLES =
        "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    /** Holding is part of ordinary booking, so reception needs it. */
    private static final String BOOKING_ROLES =
        "hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private final SlotInventoryService slotInventoryService;

    @GetMapping("/search")
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "Find open slots",
        description = "Defaults to the next two weeks. Never returns a slot in the past, and "
            + "treats an expired hold as available.")
    public ResponseEntity<List<AppointmentSlotDTO>> search(
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) UUID staffId,
        @RequestParam(required = false) UUID visitTypeId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(
            slotInventoryService.searchOpen(departmentId, staffId, visitTypeId, from, to, limit));
    }

    @PostMapping("/generate")
    @PreAuthorize(ADMIN_ROLES)
    @Operation(summary = "Materialise slots from the active session templates",
        description = "Idempotent — re-running over an overlapping window creates nothing new. "
            + "Intended to be run on a rolling horizon.")
    public ResponseEntity<SlotGenerationResultDTO> generate(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(slotInventoryService.generate(from, to));
    }

    @PostMapping("/{id}/hold")
    @PreAuthorize(BOOKING_ROLES)
    @Operation(summary = "Hold a slot while a booking completes")
    public ResponseEntity<AppointmentSlotDTO> hold(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "10") int minutes) {
        return ResponseEntity.ok(slotInventoryService.hold(id, minutes));
    }

    @PostMapping("/{id}/book")
    @PreAuthorize(BOOKING_ROLES)
    @Operation(summary = "Book a slot for a patient",
        description = "Creates a normal appointment and stamps the slot BOOKED. The appointment "
            + "owns the time from then on; cancelling it frees the slot.")
    public ResponseEntity<AppointmentSlotDTO> book(
        @PathVariable UUID id,
        @Valid @RequestBody SlotBookingRequestDTO request) {
        return ResponseEntity.ok(
            slotInventoryService.book(id, request.getPatientId(), request.getReason()));
    }

    @PostMapping("/{id}/release")
    @PreAuthorize(BOOKING_ROLES)
    @Operation(summary = "Release a held slot")
    public ResponseEntity<AppointmentSlotDTO> release(@PathVariable UUID id) {
        return ResponseEntity.ok(slotInventoryService.release(id));
    }

    @PostMapping("/{id}/block")
    @PreAuthorize(ADMIN_ROLES)
    @Operation(summary = "Take a slot out of circulation")
    public ResponseEntity<AppointmentSlotDTO> block(
        @PathVariable UUID id,
        @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(slotInventoryService.block(id, reason));
    }
}
