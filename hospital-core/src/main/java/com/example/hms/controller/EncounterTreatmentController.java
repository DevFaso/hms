package com.example.hms.controller;

import com.example.hms.payload.dto.EncounterTreatmentRequestDTO;
import com.example.hms.payload.dto.EncounterTreatmentResponseDTO;
import com.example.hms.service.EncounterTreatmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/encounter-treatments")
@RequiredArgsConstructor
@Tag(name = "Encounter Treatments", description = "APIs for recording and retrieving treatments performed during patient encounters")
public class EncounterTreatmentController {

    private final EncounterTreatmentService encounterTreatmentService;

    @Operation(
            summary = "Add a treatment to an encounter",
            description = "Records that a specific treatment from the hospital's catalog was performed during a patient encounter. " +
                    "Can include the staff member who performed the treatment, when it was performed, notes, and outcome.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Treatment performed during the encounter",
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = EncounterTreatmentRequestDTO.class)
                    )
            ),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Treatment successfully recorded for encounter",
                            content = @Content(
                                    schema = @Schema(implementation = EncounterTreatmentResponseDTO.class)
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid request data",
                            content = @Content
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Encounter or Treatment not found",
                            content = @Content
                    )
            }
    )
        @PostMapping
        @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
        public ResponseEntity<EncounterTreatmentResponseDTO> addTreatmentToEncounter(
            @Valid @RequestBody EncounterTreatmentRequestDTO dto) {
        return ResponseEntity.ok(encounterTreatmentService.addTreatmentToEncounter(dto));
    }

    @Operation(
            summary = "Get treatments performed during an encounter",
            description = "Returns a list of all treatments recorded for a specific patient encounter.",
            parameters = {
                    @Parameter(
                            name = "encounterId",
                            description = "UUID of the encounter",
                            required = true,
                            example = "d87ecf4f-7f35-4a99-9e8a-2a0b2c8580a3"
                    )
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "List of treatments performed in this encounter",
                            content = @Content(
                                    schema = @Schema(implementation = EncounterTreatmentResponseDTO.class)
                            )
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Encounter not found",
                            content = @Content
                    )
            }
    )
        @GetMapping("/by-encounter/{encounterId}")
        @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
        public ResponseEntity<List<EncounterTreatmentResponseDTO>> getTreatmentsByEncounter(
            @PathVariable UUID encounterId) {
        return ResponseEntity.ok(encounterTreatmentService.getTreatmentsByEncounter(encounterId));
    }
}
