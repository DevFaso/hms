package com.example.hms.controller;

import com.example.hms.enums.TreatmentPlanStatus;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanFollowUpDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanFollowUpRequestDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanRequestDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanResponseDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanReviewDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanReviewRequestDTO;
import com.example.hms.service.TreatmentPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/treatment-plans")
@RequiredArgsConstructor
@Tag(name = "Treatment Plans", description = "Structured patient care planning")
public class TreatmentPlanController {

    private final TreatmentPlanService treatmentPlanService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Create a treatment plan")
    public ResponseEntity<TreatmentPlanResponseDTO> create(@Valid @RequestBody TreatmentPlanRequestDTO requestDTO) {
        return ResponseEntity.ok(treatmentPlanService.create(requestDTO));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Update a treatment plan")
    public ResponseEntity<TreatmentPlanResponseDTO> update(@PathVariable UUID id,
                                                           @Valid @RequestBody TreatmentPlanRequestDTO requestDTO) {
        return ResponseEntity.ok(treatmentPlanService.update(id, requestDTO));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN','ROLE_RECEPTIONIST')")
    @Operation(summary = "Get treatment plan by id")
    public ResponseEntity<TreatmentPlanResponseDTO> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(treatmentPlanService.getById(id));
    }

    @GetMapping("/by-patient/{patientId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "List treatment plans for a patient")
    public ResponseEntity<Page<TreatmentPlanResponseDTO>> byPatient(@PathVariable UUID patientId, Pageable pageable) {
        return ResponseEntity.ok(treatmentPlanService.listByPatient(patientId, pageable));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "List all treatment plans across all hospitals (super admin)")
    public ResponseEntity<Page<TreatmentPlanResponseDTO>> listAll(
        @RequestParam(required = false) TreatmentPlanStatus status,
        Pageable pageable
    ) {
        return ResponseEntity.ok(treatmentPlanService.listAll(status, pageable));
    }

    @GetMapping("/by-hospital/{hospitalId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "List treatment plans for a hospital", description = "Optional status filter")
    public ResponseEntity<Page<TreatmentPlanResponseDTO>> byHospital(@PathVariable UUID hospitalId,
                                                                     @RequestParam(required = false) TreatmentPlanStatus status,
                                                                     Pageable pageable) {
        return ResponseEntity.ok(treatmentPlanService.listByHospital(hospitalId, status, pageable));
    }

    @PostMapping("/{id}/follow-ups")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Add follow-up task to treatment plan")
    public ResponseEntity<TreatmentPlanFollowUpDTO> addFollowUp(@PathVariable UUID id,
                                                                @Valid @RequestBody TreatmentPlanFollowUpRequestDTO requestDTO) {
        return ResponseEntity.ok(treatmentPlanService.addFollowUp(id, requestDTO));
    }

    @PutMapping("/{id}/follow-ups/{followUpId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Update follow-up task on treatment plan")
    public ResponseEntity<TreatmentPlanFollowUpDTO> updateFollowUp(@PathVariable UUID id,
                                                                   @PathVariable UUID followUpId,
                                                                   @Valid @RequestBody TreatmentPlanFollowUpRequestDTO requestDTO) {
        return ResponseEntity.ok(treatmentPlanService.updateFollowUp(id, followUpId, requestDTO));
    }

    @PostMapping("/{id}/reviews")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Add review entry for treatment plan")
    public ResponseEntity<TreatmentPlanReviewDTO> addReview(@PathVariable UUID id,
                                                            @Valid @RequestBody TreatmentPlanReviewRequestDTO requestDTO) {
        return ResponseEntity.ok(treatmentPlanService.addReview(id, requestDTO));
    }
}
