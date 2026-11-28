package com.example.hms.service;

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

import java.util.List;
import java.util.UUID;

/**
 * The transfusion loop (Tier 2 item 28): type &amp; screen → request →
 * crossmatch → issue → administer → reaction.
 *
 * <p>Before this, {@code bloodProductsRequired} — one boolean on
 * {@code ProcedureOrder} — was the entire footprint of transfusion in the
 * codebase, while the L&amp;D module (PR #437) raised postpartum-haemorrhage
 * alerts with nowhere to record the intervention that answers them.
 *
 * <p>Every method is hospital-scoped against the caller's active assignment
 * ({@code null} scope = super-admin, unscoped) and every miss is a 404 rather
 * than a 403, matching the stance the rest of the clinical surfaces take.
 *
 * <p>The safety rules live HERE rather than in the UI, and they fail closed:
 * <ul>
 *   <li>A crossmatch cannot be recorded compatible for a pair
 *       {@code AboGroup.isCompatible} rejects. The lab's tick box does not
 *       overrule antigen biology.</li>
 *   <li>A unit cannot be issued without a compatible, unexpired crossmatch —
 *       except on the emergency path, which records that it was used.</li>
 *   <li>A unit cannot be hung without two different staff performing the
 *       bedside check.</li>
 *   <li>An expired unit cannot be crossmatched, issued or hung.</li>
 *   <li>Recording a reaction stops the administration.</li>
 * </ul>
 */
public interface TransfusionService {

    // ── Type and screen ─────────────────────────────────────────────────

    /**
     * Record a type and screen, superseding the patient's previous current one.
     * ABO/Rh that disagrees with the standing group is refused unless the
     * request explicitly declares it a correction — a silent group change is
     * how the wrong blood reaches a patient.
     */
    PatientBloodGroupResponseDTO recordBloodGroup(PatientBloodGroupRequestDTO request);

    PatientBloodGroupResponseDTO getCurrentBloodGroup(UUID patientId);

    /**
     * QA D3352 (r71): the patient's current type and screen at this hospital, or empty when none is on record
     * yet. Having no group is an ordinary answer (every patient before the first type and screen), not an error.
     */
    java.util.Optional<PatientBloodGroupResponseDTO> findCurrentBloodGroup(UUID patientId);

    /** QA D3351: the biologist records the antibody identified, or the referral and the decision for one request. */
    com.example.hms.payload.dto.transfusion.AntibodyStatusResponseDTO recordAntibodyWorkup(
        com.example.hms.payload.dto.transfusion.AntibodyWorkupRequestDTO request);

    /** QA D3351: the patient's allo-immunisation at the active hospital. */
    com.example.hms.payload.dto.transfusion.AntibodyStatusResponseDTO getAntibodyStatus(UUID patientId);

    List<PatientBloodGroupResponseDTO> getBloodGroupHistory(UUID patientId);

    // ── Units ───────────────────────────────────────────────────────────

    BloodUnitResponseDTO receiveUnit(BloodUnitRequestDTO request);

    List<BloodUnitResponseDTO> listUnits(String status);

    /** Units that can still be committed: on hand, in date, not reserved. */
    List<BloodUnitResponseDTO> listAssignableUnits();

    BloodUnitResponseDTO discardUnit(UUID unitId, String reason);

    /** QA D4178: the laboratory records what became of a bag it issued and that was never hung. */
    BloodUnitResponseDTO recordUnitReturn(UUID unitId,
        com.example.hms.payload.dto.transfusion.BloodUnitReturnRequestDTO request);

    // ── Requests ────────────────────────────────────────────────────────

    TransfusionRequestResponseDTO createRequest(TransfusionRequestRequestDTO request);

    TransfusionRequestResponseDTO getRequest(UUID requestId);

    List<TransfusionRequestResponseDTO> listRequests(String status);

    List<TransfusionRequestResponseDTO> listRequestsForPatient(UUID patientId);

    TransfusionRequestResponseDTO cancelRequest(UUID requestId, String reason);

    // ── Crossmatch and issue ────────────────────────────────────────────

    CrossmatchResponseDTO recordCrossmatch(UUID requestId, CrossmatchRequestDTO request);

    List<CrossmatchResponseDTO> listCrossmatches(UUID requestId);

    /** Release a crossmatched unit from the lab to the ward. */
    /** A held request stopped from the order review: the requester need not be the reviewer. */
    void stopHeldRequest(UUID requestId, String reason);

    BloodUnitResponseDTO issueUnit(UUID requestId, UUID unitId);

    /**
     * Issue a red-cell bag on a documented emergency decision: O positive where the rules ask for a
     * decision, or despite an incompatible crossmatch. The crossmatch result is left as it was.
     */
    BloodUnitResponseDTO emergencyRelease(UUID requestId, EmergencyReleaseRequestDTO release);

    /** What the emergency rules say for this request, for the form that asks. */
    EmergencyReleaseOptionsDTO emergencyReleaseOptions(UUID requestId);

    // ── The O positive authority rule (V380) ────────────────────────────

    /** The emergency O RhD positive protocol of the acting hospital; the defaults, inactive, when it wrote none. */
    com.example.hms.payload.dto.transfusion.OPositiveProtocolDTO getPositiveProtocol();

    /** The biologist of the acting hospital turns the protocol on and sets it. */
    com.example.hms.payload.dto.transfusion.OPositiveProtocolDTO setPositiveProtocol(
        com.example.hms.payload.dto.transfusion.OPositiveProtocolDTO update);

    /** The O positive releases of the acting hospital: all, or those with something still owed. */
    java.util.List<com.example.hms.payload.dto.transfusion.OPositiveReleaseDecisionDTO> listPositiveReleases(boolean openOnly);

    /** A physician who did not issue the bag signs the order it went out on. */
    com.example.hms.payload.dto.transfusion.OPositiveReleaseDecisionDTO countersignPositiveRelease(UUID decisionId,
        boolean noOtherPersonReachable);

    /** The review of the biologist. */
    com.example.hms.payload.dto.transfusion.OPositiveReleaseDecisionDTO reviewPositiveRelease(UUID decisionId, String note,
        boolean noOtherPersonReachable);

    /** The anti-D or exchange decision of a clinician, and that the patient was told. */
    com.example.hms.payload.dto.transfusion.OPositiveReleaseDecisionDTO recordPositiveFollowUp(UUID decisionId,
        com.example.hms.payload.dto.transfusion.OPositiveFollowUpRequestDTO request);

    // ── Release despite an incompatible crossmatch (V381) ───────────────

    /** The fallback setting of the acting hospital; off, laboratory staff, when it set nothing. */
    com.example.hms.payload.dto.transfusion.IncompatibleReleaseProtocolDTO getIncompatibleProtocol();

    /** The laboratory director of the acting hospital switches the fallback and says who may declare it. */
    com.example.hms.payload.dto.transfusion.IncompatibleReleaseProtocolDTO setIncompatibleProtocol(
        com.example.hms.payload.dto.transfusion.IncompatibleReleaseProtocolDTO update);

    /** The incompatible releases of the acting hospital: all, or those with something still owed. */
    java.util.List<com.example.hms.payload.dto.transfusion.IncompatibleReleaseDecisionDTO> listIncompatibleReleases(boolean openOnly);

    /** A physician or the laboratory director, who did not issue the bag, takes the decision a fallback left owed. */
    com.example.hms.payload.dto.transfusion.IncompatibleReleaseDecisionDTO countersignIncompatibleRelease(UUID decisionId);

    /** The checks done, what the monitoring found, and that the patient was told. */
    com.example.hms.payload.dto.transfusion.IncompatibleReleaseDecisionDTO recordIncompatibleMonitoring(UUID decisionId,
        com.example.hms.payload.dto.transfusion.IncompatibleMonitoringRequestDTO request);

    // ── Bedside ─────────────────────────────────────────────────────────

    TransfusionAdministrationResponseDTO startAdministration(TransfusionAdministrationRequestDTO request);

    /** Colleagues who can be the second check at the bedside (QA CX-008). */
    List<com.example.hms.payload.dto.pharmacy.StockWitnessCandidateDTO> listVerifierCandidates();

    TransfusionAdministrationResponseDTO completeAdministration(UUID administrationId, Integer volumeMl);

    TransfusionAdministrationResponseDTO stopAdministration(UUID administrationId, String reason);

    List<TransfusionAdministrationResponseDTO> listAdministrationsForPatient(UUID patientId);

    /** Records the reaction AND stops the administration — the protocol's first step. */
    TransfusionReactionResponseDTO recordReaction(UUID administrationId, TransfusionReactionRequestDTO request);

    List<TransfusionReactionResponseDTO> listReactionsForPatient(UUID patientId);
}
