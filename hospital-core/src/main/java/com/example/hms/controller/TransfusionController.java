package com.example.hms.controller;

import com.example.hms.payload.dto.transfusion.BloodUnitRequestDTO;
import com.example.hms.payload.dto.transfusion.BloodUnitResponseDTO;
import com.example.hms.payload.dto.transfusion.CrossmatchRequestDTO;
import com.example.hms.payload.dto.transfusion.CrossmatchResponseDTO;
import com.example.hms.payload.dto.transfusion.PatientBloodGroupRequestDTO;
import com.example.hms.payload.dto.transfusion.PatientBloodGroupResponseDTO;
import com.example.hms.payload.dto.transfusion.TransfusionAdministrationRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionAdministrationResponseDTO;
import com.example.hms.payload.dto.transfusion.TransfusionReactionRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionReactionResponseDTO;
import com.example.hms.payload.dto.transfusion.TransfusionRequestRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionRequestResponseDTO;
import com.example.hms.service.TransfusionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Blood bank and transfusion (Tier 2 item 28).
 *
 * <p>A new root rather than a subpath of {@code /lab/**} or {@code /patients/**}:
 * both of those carry SecurityConfig matchers whose first-match behaviour has
 * already produced live 403 defects (the {@code POST /lab-orders/**} matcher
 * that rejected every lab role, documented since item 19). A fresh root rides
 * {@code anyRequest().authenticated()} with no matcher to fight.
 *
 * <p>Which makes the <b>class-level {@code @PreAuthorize} load-bearing</b>: with
 * no matcher, a method whose annotation was forgotten would be reachable by
 * every authenticated principal, patients included. The class rule is the union
 * of the method rules, so an un-annotated method fails closed (the
 * {@code /recalls} precedent from PR #476).
 *
 * <p>The gates split along who actually does the work: clinicians raise
 * requests, the laboratory types, receives, crossmatches and issues, and the
 * bedside team hangs the unit and reports reactions.
 */
@RestController
@RequestMapping("/transfusions")
@Validated
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('REQUEST_BLOOD_PRODUCTS','VIEW_LAB_RESULTS') "
    + "or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','SURGEON','MIDWIFE','NURSE',"
    + "'LAB_SCIENTIST','LAB_TECHNICIAN','LAB_MANAGER','LAB_DIRECTOR')")
@Tag(name = "Transfusion", description = "Type and screen, blood units, crossmatch, administration, reactions")
public class TransfusionController {

    /** Who may order blood. PHYSICIAN and SURGEON reach this through ROLE_DOCTOR expansion (PR #488). */
    private static final String PRESCRIBER =
        "hasAuthority('REQUEST_BLOOD_PRODUCTS') or hasAnyRole('SUPER_ADMIN','DOCTOR','SURGEON','MIDWIFE')";

    /** Who runs the bench: typing, receiving units, crossmatching, issuing. */
    private static final String LABORATORY =
        "hasAnyRole('SUPER_ADMIN','LAB_SCIENTIST','LAB_TECHNICIAN','LAB_MANAGER','LAB_DIRECTOR')";

    /** Who hangs the unit and reports what happens. */
    private static final String BEDSIDE =
        "hasAnyRole('SUPER_ADMIN','DOCTOR','SURGEON','MIDWIFE','NURSE')";

    /** Who may read the record. */
    private static final String READER =
        "hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','SURGEON','MIDWIFE','NURSE',"
            + "'LAB_SCIENTIST','LAB_TECHNICIAN','LAB_MANAGER','LAB_DIRECTOR')";

    private final TransfusionService transfusionService;

    // ── Type and screen ─────────────────────────────────────────────────

    @PostMapping("/blood-groups")
    @PreAuthorize(LABORATORY)
    @Operation(summary = "Record a type and screen",
               description = "Supersedes the patient's previous current result. An ABO/Rh that "
                   + "disagrees with the standing group requires a correction reason.")
    public ResponseEntity<PatientBloodGroupResponseDTO> recordBloodGroup(
        @Valid @RequestBody PatientBloodGroupRequestDTO request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transfusionService.recordBloodGroup(request));
    }

    @GetMapping("/blood-groups/patient/{patientId}")
    @PreAuthorize(READER)
    @Operation(summary = "Current type and screen for a patient")
    public ResponseEntity<PatientBloodGroupResponseDTO> getCurrentBloodGroup(@PathVariable UUID patientId) {
        return ResponseEntity.ok(transfusionService.getCurrentBloodGroup(patientId));
    }

    @GetMapping("/blood-groups/patient/{patientId}/history")
    @PreAuthorize(READER)
    @Operation(summary = "All type and screen results for a patient, newest first")
    public ResponseEntity<List<PatientBloodGroupResponseDTO>> getBloodGroupHistory(@PathVariable UUID patientId) {
        return ResponseEntity.ok(transfusionService.getBloodGroupHistory(patientId));
    }

    // ── Units ───────────────────────────────────────────────────────────

    @PostMapping("/units")
    @PreAuthorize(LABORATORY)
    @Operation(summary = "Receive a blood unit into the facility",
               description = "Unit numbers are unique per hospital and an already-expired unit is refused.")
    public ResponseEntity<BloodUnitResponseDTO> receiveUnit(@Valid @RequestBody BloodUnitRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transfusionService.receiveUnit(request));
    }

    @GetMapping("/units")
    @PreAuthorize(READER)
    @Operation(summary = "List blood units, optionally by status")
    public ResponseEntity<List<BloodUnitResponseDTO>> listUnits(
        @RequestParam(required = false) String status
    ) {
        return ResponseEntity.ok(transfusionService.listUnits(status));
    }

    @GetMapping("/units/assignable")
    @PreAuthorize(READER)
    @Operation(summary = "Units that can still be committed to a patient",
               description = "On hand, in date, not already reserved. Shortest-dated first.")
    public ResponseEntity<List<BloodUnitResponseDTO>> listAssignableUnits() {
        return ResponseEntity.ok(transfusionService.listAssignableUnits());
    }

    @PostMapping("/units/{unitId}/discard")
    @PreAuthorize(LABORATORY)
    @Operation(summary = "Discard a unit", description = "A reason is required.")
    public ResponseEntity<BloodUnitResponseDTO> discardUnit(
        @PathVariable UUID unitId,
        @RequestParam String reason
    ) {
        return ResponseEntity.ok(transfusionService.discardUnit(unitId, reason));
    }

    // ── Requests ────────────────────────────────────────────────────────

    @PostMapping("/requests")
    @PreAuthorize(PRESCRIBER)
    @Operation(summary = "Request blood components",
               description = "Requires a type and screen on file unless raised as EMERGENCY.")
    public ResponseEntity<TransfusionRequestResponseDTO> createRequest(
        @Valid @RequestBody TransfusionRequestRequestDTO request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transfusionService.createRequest(request));
    }

    @GetMapping("/requests/{requestId}")
    @PreAuthorize(READER)
    @Operation(summary = "One request with its units and crossmatches")
    public ResponseEntity<TransfusionRequestResponseDTO> getRequest(@PathVariable UUID requestId) {
        return ResponseEntity.ok(transfusionService.getRequest(requestId));
    }

    @GetMapping("/requests")
    @PreAuthorize(READER)
    @Operation(summary = "List requests for the active hospital, optionally by status")
    public ResponseEntity<List<TransfusionRequestResponseDTO>> listRequests(
        @RequestParam(required = false) String status
    ) {
        return ResponseEntity.ok(transfusionService.listRequests(status));
    }

    @GetMapping("/requests/patient/{patientId}")
    @PreAuthorize(READER)
    @Operation(summary = "Transfusion requests for one patient")
    public ResponseEntity<List<TransfusionRequestResponseDTO>> listRequestsForPatient(
        @PathVariable UUID patientId
    ) {
        return ResponseEntity.ok(transfusionService.listRequestsForPatient(patientId));
    }

    @PostMapping("/requests/{requestId}/cancel")
    @PreAuthorize(PRESCRIBER)
    @Operation(summary = "Cancel a request", description = "Releases any held units. A reason is required.")
    public ResponseEntity<TransfusionRequestResponseDTO> cancelRequest(
        @PathVariable UUID requestId,
        @RequestParam String reason
    ) {
        return ResponseEntity.ok(transfusionService.cancelRequest(requestId, reason));
    }

    // ── Crossmatch and issue ────────────────────────────────────────────

    @PostMapping("/requests/{requestId}/crossmatch")
    @PreAuthorize(LABORATORY)
    @Operation(summary = "Record a crossmatch verdict",
               description = "Refused when the pair is ABO/Rh incompatible, when the antibody screen "
                   + "has lapsed, or when the unit is expired or the wrong product.")
    public ResponseEntity<CrossmatchResponseDTO> recordCrossmatch(
        @PathVariable UUID requestId,
        @Valid @RequestBody CrossmatchRequestDTO request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(transfusionService.recordCrossmatch(requestId, request));
    }

    @GetMapping("/requests/{requestId}/crossmatch")
    @PreAuthorize(READER)
    @Operation(summary = "Crossmatch verdicts for a request")
    public ResponseEntity<List<CrossmatchResponseDTO>> listCrossmatches(@PathVariable UUID requestId) {
        return ResponseEntity.ok(transfusionService.listCrossmatches(requestId));
    }

    @PostMapping("/requests/{requestId}/issue/{unitId}")
    @PreAuthorize(LABORATORY)
    @Operation(summary = "Issue a unit from the lab to the ward",
               description = "Requires a compatible, unexpired crossmatch — except group O Rh-negative "
                   + "on an EMERGENCY request, which may be released uncrossmatched.")
    public ResponseEntity<BloodUnitResponseDTO> issueUnit(
        @PathVariable UUID requestId,
        @PathVariable UUID unitId
    ) {
        return ResponseEntity.ok(transfusionService.issueUnit(requestId, unitId));
    }

    // ── Bedside ─────────────────────────────────────────────────────────

    @PostMapping("/administrations")
    @PreAuthorize(BEDSIDE)
    @Operation(summary = "Hang an issued unit",
               description = "Requires a second clinician who independently verified the unit against "
                   + "the patient. The two cannot be the same person.")
    public ResponseEntity<TransfusionAdministrationResponseDTO> startAdministration(
        @Valid @RequestBody TransfusionAdministrationRequestDTO request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(transfusionService.startAdministration(request));
    }

    @PostMapping("/administrations/{administrationId}/complete")
    @PreAuthorize(BEDSIDE)
    @Operation(summary = "Record that a unit finished")
    public ResponseEntity<TransfusionAdministrationResponseDTO> completeAdministration(
        @PathVariable UUID administrationId,
        @RequestParam(required = false) Integer volumeMl
    ) {
        return ResponseEntity.ok(transfusionService.completeAdministration(administrationId, volumeMl));
    }

    @PostMapping("/administrations/{administrationId}/stop")
    @PreAuthorize(BEDSIDE)
    @Operation(summary = "Stop a transfusion in progress", description = "A reason is required.")
    public ResponseEntity<TransfusionAdministrationResponseDTO> stopAdministration(
        @PathVariable UUID administrationId,
        @RequestParam String reason
    ) {
        return ResponseEntity.ok(transfusionService.stopAdministration(administrationId, reason));
    }

    @GetMapping("/administrations/patient/{patientId}")
    @PreAuthorize(READER)
    @Operation(summary = "Transfusion history for one patient")
    public ResponseEntity<List<TransfusionAdministrationResponseDTO>> listAdministrationsForPatient(
        @PathVariable UUID patientId
    ) {
        return ResponseEntity.ok(transfusionService.listAdministrationsForPatient(patientId));
    }

    @PostMapping("/administrations/{administrationId}/reaction")
    @PreAuthorize(BEDSIDE)
    @Operation(summary = "Report a transfusion reaction",
               description = "Stops the transfusion and quarantines the unit — the first step of every "
                   + "reaction protocol, so the record cannot show the infusion still running.")
    public ResponseEntity<TransfusionReactionResponseDTO> recordReaction(
        @PathVariable UUID administrationId,
        @Valid @RequestBody TransfusionReactionRequestDTO request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(transfusionService.recordReaction(administrationId, request));
    }

    @GetMapping("/reactions/patient/{patientId}")
    @PreAuthorize(READER)
    @Operation(summary = "Transfusion reactions for one patient")
    public ResponseEntity<List<TransfusionReactionResponseDTO>> listReactionsForPatient(
        @PathVariable UUID patientId
    ) {
        return ResponseEntity.ok(transfusionService.listReactionsForPatient(patientId));
    }
}
