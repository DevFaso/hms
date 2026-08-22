package com.example.hms.controller;

import com.example.hms.enums.InteractionSeverity;
import com.example.hms.payload.dto.medication.DrugInteractionDTO;
import com.example.hms.service.DrugInteractionAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import java.util.List;
import java.util.UUID;

/**
 * Drug-interaction knowledge-base curation (P2 #14).
 *
 * <p>The checking pipeline has been real at prescribe, dispense and CDS-Hooks
 * since it shipped; the knowledge base behind it was twelve rows. V120 widens
 * the seed, but the durable fix is this: letting the pharmacy team maintain
 * clinical content without a developer writing a migration.
 *
 * <p>Pharmacist-and-above only. The KB drives blocking alerts at dispense, so
 * write access here is effectively write access to whether a prescription can
 * be filled.
 */
@RestController
@RequestMapping(value = "/drug-interactions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Drug Interactions", description = "Curate the drug-interaction knowledge base")
@SecurityRequirement(name = "bearerAuth")
public class DrugInteractionAdminController {

    private static final String READ_ROLES =
        "hasAnyAuthority('ROLE_PHARMACIST','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE',"
            + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private static final String WRITE_ROLES =
        "hasAnyAuthority('ROLE_PHARMACIST','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private final DrugInteractionAdminService service;

    @GetMapping
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "List known drug interactions")
    public ResponseEntity<List<DrugInteractionDTO>> list(
        @RequestParam(required = false) InteractionSeverity severity,
        @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ResponseEntity.ok(service.list(severity, activeOnly));
    }

    @GetMapping("/drug/{drugCode}")
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "List interactions involving one drug")
    public ResponseEntity<List<DrugInteractionDTO>> findForDrug(@PathVariable String drugCode) {
        return ResponseEntity.ok(service.findForDrug(drugCode));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(WRITE_ROLES)
    @Operation(summary = "Add an interaction to the knowledge base")
    public ResponseEntity<DrugInteractionDTO> create(@Valid @RequestBody DrugInteractionDTO request) {
        return new ResponseEntity<>(service.create(request), HttpStatus.CREATED);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(WRITE_ROLES)
    @Operation(summary = "Correct an interaction")
    public ResponseEntity<DrugInteractionDTO> update(
        @PathVariable UUID id,
        @Valid @RequestBody DrugInteractionDTO request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    /**
     * Deactivate, not delete. An interaction that once fired is part of why a
     * prescriber saw the alert they saw; deleting the row rewrites that history.
     */
    @PutMapping("/{id}/deactivate")
    @PreAuthorize(WRITE_ROLES)
    @Operation(summary = "Retire an interaction",
        description = "Marks it inactive. Entries are never deleted — one that has fired is part "
            + "of the record of what a prescriber was shown.")
    public ResponseEntity<DrugInteractionDTO> deactivate(@PathVariable UUID id) {
        return ResponseEntity.ok(service.deactivate(id));
    }

    /**
     * The undo deactivate never had. Every read hard-filters {@code active =
     * true} and the KB is platform-global, so before this endpoint one
     * pharmacist's deactivation silenced a pair permanently for every hospital
     * in the deployment.
     */
    @PutMapping("/{id}/reactivate")
    @PreAuthorize(WRITE_ROLES)
    @Operation(summary = "Reactivate a retired interaction",
        description = "Puts a retired entry back into every checking layer.")
    public ResponseEntity<DrugInteractionDTO> reactivate(@PathVariable UUID id) {
        return ResponseEntity.ok(service.reactivate(id));
    }
}
