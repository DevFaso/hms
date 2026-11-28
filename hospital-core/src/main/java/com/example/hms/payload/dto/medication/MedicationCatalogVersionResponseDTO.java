package com.example.hms.payload.dto.medication;

import com.example.hms.enums.MedicationCatalogChangeType;
import com.example.hms.enums.MedicationCatalogVersionKind;
import com.example.hms.enums.MedicationCatalogVersionStatus;
import com.example.hms.model.medication.DoseLimitSnapshot;
import com.example.hms.model.medication.FormularySnapshot;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Go-live D2 (V385): one version of a medication catalogue entry or of its dose limits, as the approval queue
 * and the history show it: what is proposed, what was in force, who, when and why.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MedicationCatalogVersionResponseDTO {

    private UUID id;
    /** Null for a platform entry. */
    private UUID hospitalId;
    /** Null while a proposed new entry is not in the catalogue yet. */
    private UUID catalogItemId;
    private MedicationCatalogVersionKind kind;
    private MedicationCatalogChangeType changeType;
    private int versionNumber;
    private MedicationCatalogVersionStatus status;
    private String itemLabel;

    /** The proposed entry, for a FORMULARY version. */
    private FormularySnapshot formulary;
    /** The entry in force when it was proposed. */
    private FormularySnapshot previousFormulary;
    /** The proposed limits, for a DOSE_LIMIT version. */
    private DoseLimitSnapshot doseLimits;
    /** The limits in force when they were proposed. */
    private DoseLimitSnapshot previousDoseLimits;
    /** The fields that differ from what was in force; empty for a new entry or an unchanged submission. */
    private List<String> changedFields;

    private String changeReason;
    private UUID proposedById;
    private String proposedByName;
    private LocalDateTime proposedAt;
    private UUID decidedById;
    private String decidedByName;
    private LocalDateTime decidedAt;
    private String decisionReason;
    /** On what authority it was approved or refused; MEDICAL_LEAD_NO_PHARMACIST is the no-pharmacist fallback. */
    private com.example.hms.enums.MedicationCatalogDecisionBasis decisionBasis;

    /** Whether the caller may approve it: the right role here, and not their own proposal. */
    private boolean callerMayApprove;
    /** Whether the caller may refuse it (the right role here). */
    private boolean callerMayReject;
    /** Whether the caller proposed it (and may take it back while it waits). */
    private boolean ownProposal;

    /**
     * QA D4180 (r81): for a formulary version still waiting, the entries of the catalogue (and the other waiting
     * proposals) that look like it, so that the approver sees a duplicate before approving it.
     */
    private List<MedicationCatalogSimilarEntryDTO> similarEntries;
}
