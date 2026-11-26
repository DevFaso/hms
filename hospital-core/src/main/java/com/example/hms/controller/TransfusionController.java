package com.example.hms.controller;

import com.example.hms.payload.dto.transfusion.BloodUnitRequestDTO;
import com.example.hms.payload.dto.transfusion.BloodUnitResponseDTO;
import com.example.hms.payload.dto.transfusion.CrossmatchRequestDTO;
import com.example.hms.payload.dto.transfusion.CrossmatchResponseDTO;
import com.example.hms.payload.dto.transfusion.EmergencyReleaseOptionsDTO;
import com.example.hms.payload.dto.transfusion.EmergencyReleaseRequestDTO;
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
import org.springframework.web.bind.annotation.PutMapping;
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
@PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR','SURGEON','MIDWIFE','NURSE','LAB_SCIENTIST','LAB_TECHNICIAN','LAB_MANAGER','LAB_DIRECTOR')")
@Tag(name = "Transfusion", description = "Type and screen, blood units, crossmatch, administration, reactions")
public class TransfusionController {

    /**
     * Who may order blood. PHYSICIAN and SURGEON reach this through ROLE_DOCTOR
     * expansion (PR #488). It once also named the permission
     * {@code REQUEST_BLOOD_PRODUCTS}, which no authority carries (authorities
     * are roles only), so that clause never matched; the role list was always
     * the whole guard.
     */
    private static final String PRESCRIBER =
        "T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR','SURGEON','MIDWIFE')";

    /** Who runs the bench: typing, receiving units, crossmatching, issuing. */
    private static final String LABORATORY =
        "hasAnyRole('SUPER_ADMIN','LAB_SCIENTIST','LAB_TECHNICIAN','LAB_MANAGER','LAB_DIRECTOR')";

    /** Who hangs the unit and reports what happens. */
    private static final String BEDSIDE =
        "hasAnyRole('SUPER_ADMIN','DOCTOR','SURGEON','MIDWIFE','NURSE')";

    /** Who may read the record. */
    private static final String READER =
        "hasAnyRole('SUPER_ADMIN','DOCTOR','SURGEON','MIDWIFE','NURSE',"
            + "'LAB_SCIENTIST','LAB_TECHNICIAN','LAB_MANAGER','LAB_DIRECTOR')";

    private final TransfusionService transfusionService;

    // ── Type and screen ─────────────────────────────────────────────────

    @PostMapping("/blood-groups")
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'LAB_SCIENTIST','LAB_TECHNICIAN','LAB_MANAGER','LAB_DIRECTOR')")
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
    @Operation(summary = "Current type and screen for a patient",
        description = "204 No Content when the patient has no type and screen at this hospital yet (QA D3352): "
            + "an ordinary answer before the first one, not a missing resource.")
    public ResponseEntity<PatientBloodGroupResponseDTO> getCurrentBloodGroup(@PathVariable UUID patientId) {
        return transfusionService.findCurrentBloodGroup(patientId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/antibody-workups")
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'LAB_DIRECTOR')")
    @Operation(summary = "Record the antibody identified, or the referral decision for one request",
        description = "The hospital's biologist only (rule set TRANSFUSION_POSITIVE_ANTIBODY_SCREEN); "
            + "a super-admin alone is refused.")
    public ResponseEntity<com.example.hms.payload.dto.transfusion.AntibodyStatusResponseDTO> recordAntibodyWorkup(
        @Valid @RequestBody com.example.hms.payload.dto.transfusion.AntibodyWorkupRequestDTO request) {
        return new ResponseEntity<>(transfusionService.recordAntibodyWorkup(request), HttpStatus.CREATED);
    }

    @GetMapping("/antibody-status/patient/{patientId}")
    @PreAuthorize(READER)
    @Operation(summary = "The patient's allo-immunisation, wherever it is on record",
        description = "The permanent flag of a positive antibody screen and the antibody identified, for everyone "
            + "on the transfusion path: the bench, the prescriber and the nurse who gives the unit. Of another "
            + "hospital's record: the antibody, the date and that hospital's name only. Each read is accounted.")
    public ResponseEntity<com.example.hms.payload.dto.transfusion.AntibodyStatusResponseDTO> getAntibodyStatus(
        @PathVariable UUID patientId) {
        return ResponseEntity.ok(transfusionService.getAntibodyStatus(patientId));
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
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'LAB_SCIENTIST','LAB_TECHNICIAN','LAB_MANAGER','LAB_DIRECTOR')")
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
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'LAB_SCIENTIST','LAB_TECHNICIAN','LAB_MANAGER','LAB_DIRECTOR')")
    @Operation(summary = "Issue a unit from the lab to the ward",
               description = "Requires a compatible, unexpired crossmatch — except group O red cells on an "
                   + "EMERGENCY request, as the emergency release rules allow.")
    public ResponseEntity<BloodUnitResponseDTO> issueUnit(
        @PathVariable UUID requestId,
        @PathVariable UUID unitId
    ) {
        return ResponseEntity.ok(transfusionService.issueUnit(requestId, unitId));
    }

    @GetMapping("/requests/{requestId}/emergency-release/options")
    @PreAuthorize(LABORATORY)
    @Operation(summary = "What the emergency release rules say for this request")
    public ResponseEntity<EmergencyReleaseOptionsDTO> emergencyReleaseOptions(@PathVariable UUID requestId) {
        return ResponseEntity.ok(transfusionService.emergencyReleaseOptions(requestId));
    }

    @PostMapping("/requests/{requestId}/emergency-release")
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'LAB_SCIENTIST','LAB_TECHNICIAN','LAB_MANAGER','LAB_DIRECTOR')")
    @Operation(summary = "Issue a red-cell unit on a documented emergency decision",
               description = "O positive on a documented decision, or serologically incompatible on a reason and "
                   + "a named doctor or laboratory director who is not the caller. An ABO exclusion stays refused.")
    public ResponseEntity<BloodUnitResponseDTO> emergencyRelease(
        @PathVariable UUID requestId,
        @Valid @RequestBody EmergencyReleaseRequestDTO release
    ) {
        return ResponseEntity.ok(transfusionService.emergencyRelease(requestId, release));
    }

    // ── The O positive authority rule (V380) ────────────────────────────

    /** Who may countersign or review an O positive release; the service checks the role at the acting hospital. */
    private static final String PHYSICIAN_OR_BIOLOGIST =
        "T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR','SURGEON','LAB_DIRECTOR')";

    @GetMapping("/o-positive-protocol")
    @PreAuthorize(READER)
    @Operation(summary = "The hospital's emergency O RhD positive protocol",
        description = "Whether a written protocol is active, the age cut-off for women, whether boys are in the "
            + "low-risk tier, and the review deadline. With no active protocol every release is handled as high-risk.")
    public ResponseEntity<com.example.hms.payload.dto.transfusion.OPositiveProtocolDTO> getPositiveProtocol() {
        return ResponseEntity.ok(transfusionService.getPositiveProtocol());
    }

    @PutMapping("/o-positive-protocol")
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'LAB_DIRECTOR')")
    @com.example.hms.security.audit.WriteAudited(skip = true, reason = "service emits CONFIGURATION_CHANGED with the new values")
    @Operation(summary = "Set the hospital's emergency O RhD positive protocol",
        description = "The hospital's biologist only; a super-admin alone is refused.")
    public ResponseEntity<com.example.hms.payload.dto.transfusion.OPositiveProtocolDTO> setPositiveProtocol(
        @Valid @RequestBody com.example.hms.payload.dto.transfusion.OPositiveProtocolDTO update) {
        return ResponseEntity.ok(transfusionService.setPositiveProtocol(update));
    }

    @GetMapping("/o-positive-releases")
    @PreAuthorize(READER)
    @Operation(summary = "The hospital's emergency O RhD positive releases",
        description = "Each with its tier, who decided, and what is still owed: countersignature, review, follow-up.")
    public ResponseEntity<List<com.example.hms.payload.dto.transfusion.OPositiveReleaseDecisionDTO>> listPositiveReleases(
        @RequestParam(defaultValue = "true") boolean openOnly) {
        return ResponseEntity.ok(transfusionService.listPositiveReleases(openOnly));
    }

    @PostMapping("/o-positive-releases/{decisionId}/countersign")
    @PreAuthorize(PHYSICIAN_OR_BIOLOGIST)
    @Operation(summary = "Countersign the order an O positive bag went out on",
        description = "A physician of the hospital who did not issue the bag; the laboratory director too outside "
            + "the known-antibody tier.")
    public ResponseEntity<com.example.hms.payload.dto.transfusion.OPositiveReleaseDecisionDTO> countersignPositiveRelease(
        @PathVariable UUID decisionId,
        @RequestBody(required = false) com.example.hms.payload.dto.transfusion.OPositiveReviewRequestDTO body) {
        return ResponseEntity.ok(transfusionService.countersignPositiveRelease(decisionId, waived(body)));
    }

    /** Known antibody: the caller states that nobody else can be reached for the second of the two steps. */
    private static boolean waived(com.example.hms.payload.dto.transfusion.OPositiveReviewRequestDTO body) {
        return body != null && Boolean.TRUE.equals(body.getNoOtherPersonReachable());
    }

    @PostMapping("/o-positive-releases/{decisionId}/review")
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'LAB_DIRECTOR')")
    @Operation(summary = "The biologist's review of an O positive release")
    public ResponseEntity<com.example.hms.payload.dto.transfusion.OPositiveReleaseDecisionDTO> reviewPositiveRelease(
        @PathVariable UUID decisionId,
        @RequestBody(required = false) com.example.hms.payload.dto.transfusion.OPositiveReviewRequestDTO review) {
        return ResponseEntity.ok(transfusionService.reviewPositiveRelease(decisionId, review == null ? null : review.getNote(),
            waived(review)));
    }

    @PostMapping("/o-positive-releases/{decisionId}/follow-up")
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR','SURGEON')")
    @Operation(summary = "Record the follow-up of an RhD positive exposure",
        description = "The anti-D or exchange decision of a clinician, and that the patient was told.")
    public ResponseEntity<com.example.hms.payload.dto.transfusion.OPositiveReleaseDecisionDTO> recordPositiveFollowUp(
        @PathVariable UUID decisionId,
        @Valid @RequestBody com.example.hms.payload.dto.transfusion.OPositiveFollowUpRequestDTO request) {
        return ResponseEntity.ok(transfusionService.recordPositiveFollowUp(decisionId, request));
    }

    // ── Release despite an incompatible crossmatch (V381) ───────────────

    @GetMapping("/incompatible-release-protocol")
    @PreAuthorize(READER)
    @Operation(summary = "The hospital's fallback for an incompatible release with no physician reachable",
        description = "Whether the laboratory director switched it on, and which roles may declare that no physician "
            + "can be reached. Off by default: a named physician is then always required.")
    public ResponseEntity<com.example.hms.payload.dto.transfusion.IncompatibleReleaseProtocolDTO> getIncompatibleProtocol() {
        return ResponseEntity.ok(transfusionService.getIncompatibleProtocol());
    }

    @PutMapping("/incompatible-release-protocol")
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'LAB_DIRECTOR')")
    @com.example.hms.security.audit.WriteAudited(skip = true, reason = "service emits CONFIGURATION_CHANGED with the new values")
    @Operation(summary = "Set the hospital's fallback for an incompatible release with no physician reachable",
        description = "The hospital's laboratory director only; a super-admin alone is refused.")
    public ResponseEntity<com.example.hms.payload.dto.transfusion.IncompatibleReleaseProtocolDTO> setIncompatibleProtocol(
        @Valid @RequestBody com.example.hms.payload.dto.transfusion.IncompatibleReleaseProtocolDTO update) {
        return ResponseEntity.ok(transfusionService.setIncompatibleProtocol(update));
    }

    @GetMapping("/incompatible-releases")
    @PreAuthorize(READER)
    @Operation(summary = "The hospital's releases despite an incompatible crossmatch",
        description = "Each with its authority, the bag chosen, and what is still owed: countersignature, monitoring.")
    public ResponseEntity<List<com.example.hms.payload.dto.transfusion.IncompatibleReleaseDecisionDTO>> listIncompatibleReleases(
        @RequestParam(defaultValue = "true") boolean openOnly) {
        return ResponseEntity.ok(transfusionService.listIncompatibleReleases(openOnly));
    }

    @PostMapping("/incompatible-releases/{decisionId}/countersign")
    @PreAuthorize(PHYSICIAN_OR_BIOLOGIST)
    @Operation(summary = "Countersign an incompatible release that went out with no physician reachable",
        description = "A physician or the laboratory director of the hospital, who did not issue the bag.")
    public ResponseEntity<com.example.hms.payload.dto.transfusion.IncompatibleReleaseDecisionDTO> countersignIncompatibleRelease(
        @PathVariable UUID decisionId) {
        return ResponseEntity.ok(transfusionService.countersignIncompatibleRelease(decisionId));
    }

    @PostMapping("/incompatible-releases/{decisionId}/monitoring")
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR','SURGEON','MIDWIFE','NURSE')")
    @Operation(summary = "Record the monitoring after an incompatible release",
        description = "The checks done, whether a reaction was seen (the haemovigilance prompt), and that the patient "
            + "was told.")
    public ResponseEntity<com.example.hms.payload.dto.transfusion.IncompatibleReleaseDecisionDTO> recordIncompatibleMonitoring(
        @PathVariable UUID decisionId,
        @Valid @RequestBody com.example.hms.payload.dto.transfusion.IncompatibleMonitoringRequestDTO request) {
        return ResponseEntity.ok(transfusionService.recordIncompatibleMonitoring(decisionId, request));
    }

    // ── Bedside ─────────────────────────────────────────────────────────

    @PostMapping("/administrations")
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR','SURGEON','MIDWIFE','NURSE')")
    @Operation(summary = "Hang an issued unit",
               description = "Requires a second clinician who independently verified the unit against "
                   + "the patient. The two cannot be the same person.")
    public ResponseEntity<TransfusionAdministrationResponseDTO> startAdministration(
        @Valid @RequestBody TransfusionAdministrationRequestDTO request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(transfusionService.startAdministration(request));
    }

    @GetMapping("/verifier-candidates")
    @PreAuthorize(BEDSIDE)
    @Operation(summary = "Colleagues who can be the second check at the bedside")
    public ResponseEntity<List<com.example.hms.payload.dto.pharmacy.StockWitnessCandidateDTO>> listVerifierCandidates() {
        return ResponseEntity.ok(transfusionService.listVerifierCandidates());
    }

    @PostMapping("/administrations/{administrationId}/complete")
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR','SURGEON','MIDWIFE','NURSE')")
    @Operation(summary = "Record that a unit finished")
    public ResponseEntity<TransfusionAdministrationResponseDTO> completeAdministration(
        @PathVariable UUID administrationId,
        @RequestParam(required = false) Integer volumeMl
    ) {
        return ResponseEntity.ok(transfusionService.completeAdministration(administrationId, volumeMl));
    }

    @PostMapping("/administrations/{administrationId}/stop")
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR','SURGEON','MIDWIFE','NURSE')")
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
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR','SURGEON','MIDWIFE','NURSE')")
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
