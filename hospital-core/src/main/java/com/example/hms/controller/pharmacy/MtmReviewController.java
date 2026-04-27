package com.example.hms.controller.pharmacy;

import com.example.hms.payload.dto.pharmacy.MtmReviewRequestDTO;
import com.example.hms.payload.dto.pharmacy.MtmReviewResponseDTO;
import com.example.hms.service.pharmacy.MtmReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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

/**
 * P-09: REST endpoints for MTM (Medication Therapy Management) reviews.
 */
@RestController
@RequestMapping("/mtm-reviews")
@RequiredArgsConstructor
@Tag(name = "MTM Reviews", description = "Pharmacist-led medication therapy management reviews")
@SecurityRequirement(name = "bearerAuth")
public class MtmReviewController {

    private final MtmReviewService mtmReviewService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Start a new MTM review")
    public ResponseEntity<MtmReviewResponseDTO> start(@Valid @RequestBody MtmReviewRequestDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mtmReviewService.startReview(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Update an in-progress MTM review (intervention, status, follow-up)")
    public ResponseEntity<MtmReviewResponseDTO> update(
            @PathVariable UUID id,
            @Valid @RequestBody MtmReviewRequestDTO dto) {
        return ResponseEntity.ok(mtmReviewService.updateReview(id, dto));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_DOCTOR','ROLE_NURSE',"
            + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get an MTM review by ID")
    public ResponseEntity<MtmReviewResponseDTO> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(mtmReviewService.getReview(id));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "List MTM reviews for the active hospital")
    public ResponseEntity<Page<MtmReviewResponseDTO>> listByHospital(
            @RequestParam UUID hospitalId,
            Pageable pageable) {
        return ResponseEntity.ok(mtmReviewService.listByHospital(hospitalId, pageable));
    }

    @GetMapping("/by-patient/{patientId}")
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_DOCTOR','ROLE_NURSE',"
            + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "List MTM reviews for a specific patient")
    public ResponseEntity<Page<MtmReviewResponseDTO>> listByPatient(
            @PathVariable UUID patientId,
            Pageable pageable) {
        return ResponseEntity.ok(mtmReviewService.listByPatient(patientId, pageable));
    }
}
