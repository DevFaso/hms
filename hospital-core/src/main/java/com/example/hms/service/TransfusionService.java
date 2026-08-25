package com.example.hms.service;

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

    List<PatientBloodGroupResponseDTO> getBloodGroupHistory(UUID patientId);

    // ── Units ───────────────────────────────────────────────────────────

    BloodUnitResponseDTO receiveUnit(BloodUnitRequestDTO request);

    List<BloodUnitResponseDTO> listUnits(String status);

    /** Units that can still be committed: on hand, in date, not reserved. */
    List<BloodUnitResponseDTO> listAssignableUnits();

    BloodUnitResponseDTO discardUnit(UUID unitId, String reason);

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
    BloodUnitResponseDTO issueUnit(UUID requestId, UUID unitId);

    // ── Bedside ─────────────────────────────────────────────────────────

    TransfusionAdministrationResponseDTO startAdministration(TransfusionAdministrationRequestDTO request);

    TransfusionAdministrationResponseDTO completeAdministration(UUID administrationId, Integer volumeMl);

    TransfusionAdministrationResponseDTO stopAdministration(UUID administrationId, String reason);

    List<TransfusionAdministrationResponseDTO> listAdministrationsForPatient(UUID patientId);

    /** Records the reaction AND stops the administration — the protocol's first step. */
    TransfusionReactionResponseDTO recordReaction(UUID administrationId, TransfusionReactionRequestDTO request);

    List<TransfusionReactionResponseDTO> listReactionsForPatient(UUID patientId);
}
