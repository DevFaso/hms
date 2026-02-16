package com.example.hms.controller;

import com.example.hms.payload.dto.highrisk.HighRiskBloodPressureLogRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskCareTeamNoteRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskMedicationLogRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskPregnancyCarePlanRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskPregnancyCarePlanResponseDTO;
import com.example.hms.service.HighRiskPregnancyCarePlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/high-risk-care-plans")
@RequiredArgsConstructor
@Validated
@Tag(name = "High-Risk Pregnancy Care", description = "Clinical workflows for monitoring high-risk pregnancies")
public class HighRiskPregnancyCarePlanController {

    private final HighRiskPregnancyCarePlanService carePlanService;

    @PostMapping
    @PreAuthorize("hasAuthority('MANAGE_HIGH_RISK_PREGNANCIES') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','MIDWIFE')")
    @Operation(summary = "Create a high-risk pregnancy care plan")
    public ResponseEntity<HighRiskPregnancyCarePlanResponseDTO> createPlan(
        @Valid @RequestBody HighRiskPregnancyCarePlanRequestDTO request,
        Authentication authentication
    ) {
        HighRiskPregnancyCarePlanResponseDTO response = carePlanService.createPlan(request, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{planId}")
    @PreAuthorize("hasAuthority('MANAGE_HIGH_RISK_PREGNANCIES') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','MIDWIFE')")
    @Operation(summary = "Update an existing high-risk pregnancy care plan")
    public ResponseEntity<HighRiskPregnancyCarePlanResponseDTO> updatePlan(
        @PathVariable UUID planId,
        @Valid @RequestBody HighRiskPregnancyCarePlanRequestDTO request,
        Authentication authentication
    ) {
        HighRiskPregnancyCarePlanResponseDTO response = carePlanService.updatePlan(planId, request, authentication.getName());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{planId}")
    @PreAuthorize("hasAuthority('MANAGE_HIGH_RISK_PREGNANCIES') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','NURSE','MIDWIFE','PATIENT')")
    @Operation(summary = "Fetch a single high-risk pregnancy care plan")
    public ResponseEntity<HighRiskPregnancyCarePlanResponseDTO> getPlan(
        @PathVariable UUID planId,
        Authentication authentication
    ) {
        HighRiskPregnancyCarePlanResponseDTO response = carePlanService.getPlan(planId, authentication.getName());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAuthority('MANAGE_HIGH_RISK_PREGNANCIES') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','NURSE','MIDWIFE','PATIENT')")
    @Operation(summary = "List all plans recorded for a patient")
    public ResponseEntity<List<HighRiskPregnancyCarePlanResponseDTO>> getPlansForPatient(
        @PathVariable UUID patientId,
        Authentication authentication
    ) {
        List<HighRiskPregnancyCarePlanResponseDTO> plans = carePlanService.getPlansForPatient(patientId, authentication.getName());
        return ResponseEntity.ok(plans);
    }

    @GetMapping("/patient/{patientId}/active")
    @PreAuthorize("hasAuthority('MANAGE_HIGH_RISK_PREGNANCIES') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','NURSE','MIDWIFE','PATIENT')")
    @Operation(summary = "Retrieve the active high-risk pregnancy care plan for a patient")
    public ResponseEntity<HighRiskPregnancyCarePlanResponseDTO> getActivePlan(
        @PathVariable UUID patientId,
        Authentication authentication
    ) {
        HighRiskPregnancyCarePlanResponseDTO response = carePlanService.getActivePlan(patientId, authentication.getName());
        if (response == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{planId}/blood-pressure-logs")
    @PreAuthorize("hasAuthority('MANAGE_HIGH_RISK_PREGNANCIES') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','MIDWIFE')")
    @Operation(summary = "Append a blood pressure reading to the care plan")
    public ResponseEntity<HighRiskPregnancyCarePlanResponseDTO> addBloodPressureLog(
        @PathVariable UUID planId,
        @Valid @RequestBody HighRiskBloodPressureLogRequestDTO request,
        Authentication authentication
    ) {
        HighRiskPregnancyCarePlanResponseDTO response = carePlanService.addBloodPressureLog(planId, request, authentication.getName());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{planId}/medication-logs")
    @PreAuthorize("hasAuthority('MANAGE_HIGH_RISK_PREGNANCIES') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','MIDWIFE')")
    @Operation(summary = "Record medication adherence for the care plan")
    public ResponseEntity<HighRiskPregnancyCarePlanResponseDTO> addMedicationLog(
        @PathVariable UUID planId,
        @Valid @RequestBody HighRiskMedicationLogRequestDTO request,
        Authentication authentication
    ) {
        HighRiskPregnancyCarePlanResponseDTO response = carePlanService.addMedicationLog(planId, request, authentication.getName());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{planId}/care-team-notes")
    @PreAuthorize("hasAuthority('MANAGE_HIGH_RISK_PREGNANCIES') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','MIDWIFE','PATIENT')")
    @Operation(summary = "Capture a coordination note from the care team or patient")
    public ResponseEntity<HighRiskPregnancyCarePlanResponseDTO> addCareTeamNote(
        @PathVariable UUID planId,
        @Valid @RequestBody HighRiskCareTeamNoteRequestDTO request,
        Authentication authentication
    ) {
        HighRiskPregnancyCarePlanResponseDTO response = carePlanService.addCareTeamNote(planId, request, authentication.getName());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{planId}/milestones/{milestoneId}/complete")
    @PreAuthorize("hasAuthority('MANAGE_HIGH_RISK_PREGNANCIES') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','MIDWIFE')")
    @Operation(summary = "Mark a monitoring milestone as completed")
    public ResponseEntity<HighRiskPregnancyCarePlanResponseDTO> markMilestoneComplete(
        @PathVariable UUID planId,
        @PathVariable UUID milestoneId,
        @RequestParam(name = "completionDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate completionDate,
        Authentication authentication
    ) {
        HighRiskPregnancyCarePlanResponseDTO response = carePlanService.markMilestoneComplete(
            planId,
            milestoneId,
            completionDate,
            authentication.getName()
        );
        return ResponseEntity.ok(response);
    }
}
