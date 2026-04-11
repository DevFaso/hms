package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.clinical.PatientTrackerBoardDTO;
import com.example.hms.service.PatientTrackerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * REST controller for the hospital-wide real-time patient tracker board (MVP 5).
 */
@RestController
@RequestMapping("/patient-tracker")
@RequiredArgsConstructor
@Tag(name = "Patient Tracker", description = "Hospital-wide real-time patient status board")
public class PatientTrackerController {

    private final PatientTrackerService trackerService;
    private final ControllerAuthUtils authUtils;

    @GetMapping
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE','RECEPTIONIST','HOSPITAL_ADMIN','ADMIN','SUPER_ADMIN','MIDWIFE')")
    @Operation(summary = "Get real-time patient tracker board",
            description = "Returns all active patients for the day grouped by encounter status lanes.")
    public ResponseEntity<ApiResponseWrapper<PatientTrackerBoardDTO>> getTrackerBoard(
            Authentication auth,
            @RequestParam(required = false) UUID hospitalId,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        authUtils.requireAuth(auth);

        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        if (resolvedHospitalId == null) {
            throw new BusinessException("Hospital context is required for the patient tracker board.");
        }

        PatientTrackerBoardDTO board = trackerService.getTrackerBoard(resolvedHospitalId, departmentId, date);
        return ResponseEntity.ok(ApiResponseWrapper.success(board));
    }
}
