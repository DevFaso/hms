package com.example.hms.controller;

import com.example.hms.payload.dto.StaffLeaveDecisionDTO;
import com.example.hms.payload.dto.StaffLeaveRequestDTO;
import com.example.hms.payload.dto.StaffLeaveResponseDTO;
import com.example.hms.payload.dto.StaffShiftRequestDTO;
import com.example.hms.payload.dto.StaffShiftResponseDTO;
import com.example.hms.payload.dto.StaffShiftStatusUpdateDTO;
import com.example.hms.service.StaffSchedulingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/staff/scheduling")
@CrossOrigin(origins = "http://localhost:4200", maxAge = 3600)
@RequiredArgsConstructor
@Tag(name = "Staff Scheduling", description = "Manage staff shift planning and leave approvals.")
public class StaffSchedulingController {

    private final StaffSchedulingService schedulingService;

    @Operation(summary = "Schedule a shift for a staff member")
    @ApiResponse(responseCode = "200", description = "Shift scheduled",
        content = @Content(schema = @Schema(implementation = StaffShiftResponseDTO.class)))
    @PostMapping("/shifts")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<StaffShiftResponseDTO> scheduleShift(
        @Valid @RequestBody StaffShiftRequestDTO request,
        @RequestHeader(name = "Accept-Language", required = false) String lang
    ) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(schedulingService.scheduleShift(request, locale));
    }

    @Operation(summary = "Update an existing shift")
    @PutMapping("/shifts/{shiftId}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<StaffShiftResponseDTO> updateShift(
        @PathVariable UUID shiftId,
        @Valid @RequestBody StaffShiftRequestDTO request,
        @RequestHeader(name = "Accept-Language", required = false) String lang
    ) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(schedulingService.updateShift(shiftId, request, locale));
    }

    @Operation(summary = "Update the status of a shift (publish/cancel/complete)")
    @PatchMapping("/shifts/{shiftId}/status")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<StaffShiftResponseDTO> updateShiftStatus(
        @PathVariable UUID shiftId,
        @Valid @RequestBody StaffShiftStatusUpdateDTO request,
        @RequestHeader(name = "Accept-Language", required = false) String lang
    ) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(schedulingService.updateShiftStatus(shiftId, request, locale));
    }

    @Operation(summary = "List shifts", description = "Filter by hospital, department, staff, and date range")
    @GetMapping("/shifts")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_RECEPTIONIST','ROLE_ADMINISTRATIVE_STAFF')")
    public ResponseEntity<List<StaffShiftResponseDTO>> listShifts(
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) UUID staffId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
        @RequestHeader(name = "Accept-Language", required = false) String lang
    ) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(schedulingService.findShifts(hospitalId, departmentId, staffId, startDate, endDate, locale));
    }

    @Operation(summary = "Submit a leave request")
    @PostMapping("/leaves")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_STAFF','ROLE_RECEPTIONIST','ROLE_PHARMACIST','ROLE_RADIOLOGIST','ROLE_LAB_SCIENTIST')")
    public ResponseEntity<StaffLeaveResponseDTO> requestLeave(
        @Valid @RequestBody StaffLeaveRequestDTO request,
        @RequestHeader(name = "Accept-Language", required = false) String lang
    ) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(schedulingService.requestLeave(request, locale));
    }

    @Operation(summary = "Approve or reject a leave request")
    @PatchMapping("/leaves/{leaveId}/decision")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<StaffLeaveResponseDTO> decideLeave(
        @PathVariable UUID leaveId,
        @Valid @RequestBody StaffLeaveDecisionDTO request,
        @RequestHeader(name = "Accept-Language", required = false) String lang
    ) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(schedulingService.decideLeave(leaveId, request, locale));
    }

    @Operation(summary = "Cancel a pending leave request")
    @PatchMapping("/leaves/{leaveId}/cancel")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_RECEPTIONIST','ROLE_STAFF','ROLE_PHARMACIST','ROLE_RADIOLOGIST','ROLE_LAB_SCIENTIST')")
    public ResponseEntity<StaffLeaveResponseDTO> cancelLeave(
        @PathVariable UUID leaveId,
        @RequestHeader(name = "Accept-Language", required = false) String lang
    ) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(schedulingService.cancelLeave(leaveId, locale));
    }

    @Operation(summary = "List leave requests", description = "Filter by hospital, department, staff, and date range")
    @GetMapping("/leaves")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_RECEPTIONIST','ROLE_ADMINISTRATIVE_STAFF')")
    public ResponseEntity<List<StaffLeaveResponseDTO>> listLeaves(
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) UUID staffId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
        @RequestHeader(name = "Accept-Language", required = false) String lang
    ) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(schedulingService.findLeaves(hospitalId, departmentId, staffId, startDate, endDate, locale));
    }

    private Locale parseLocale(String header) {
        if (header == null || header.isBlank()) {
            return Locale.getDefault();
        }
        return Locale.forLanguageTag(header.split(",")[0]);
    }
}
