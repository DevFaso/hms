package com.example.hms.payload.dto;

import com.example.hms.enums.PrescriptionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@OneOf(fields = {"patientId", "patientIdentifier"}, message = "{prescription.patientId.required}")
public class PrescriptionRequestDTO {

    private UUID id; // ignored on create

    private UUID patientId;
    private String patientIdentifier; // username/email/MRN fallback

    private UUID staffId;     // prescriber

    private UUID encounterId; // anchor

    @NotBlank
    @Size(max = 255)
    private String medicationName;

    /**
     * Optional formulary code (matches {@code MedicationCatalogItem.code}
     * when the medication was picked from the catalog). When present,
     * the CDS rule engine resolves it to RxNorm/ATC/pediatric ceiling;
     * when absent, freetext-only rules (allergy, duplicate-by-name) still
     * fire but DDI and the hard pediatric ceiling stay silent.
     */
    @Size(max = 64)
    private String medicationCode;

    /**
     * D654: the catalog item the prescriber picked. When present and no
     * {@link #medicationCode} is sent, the code is taken from this item (in
     * the prescription's hospital), so the response shows the catalog's
     * controlled flag and the CDS engine sees the formulary code.
     */
    private UUID medicationCatalogItemId;

    @Size(max = 100)
    private String dosage;

    @Size(max = 100)
    private String frequency;

    /**
     * Administration route (PO, IV, IM, SC, ...). The MAR five-rights route
     * check compares the bedside scan with this value; without it every
     * order looked oral and an IV dose always failed the route right.
     */
    @Size(max = 80)
    private String route;

    @Size(max = 100)
    private String duration;

    @Size(max = 1024)
    private String notes;

    private PrescriptionStatus status;

    /**
     * Force override allergy checking (for severe allergies).
     * When true, allows prescribing despite documented severe allergies.
     * Should be used only after clinician review.
     */
    private Boolean forceOverride;

    /** Why the prescriber overrides a documented allergy; required with forceOverride on a match (QA D83). */
    @jakarta.validation.constraints.Size(max = 1024)
    private String overrideReason;

    /**
     * Flag this prescription as a controlled substance (P2 #15).
     *
     * <p>The gates guarding this flag shipped with no way to set it — the
     * columns existed since the pharmacy module and nothing ever wrote them, so
     * the enforcement could never fire. Tri-state on purpose: {@code null}
     * leaves the stored value unchanged on update; {@code false} on a
     * prescription already flagged is refused (a declared safeguard is not
     * un-declarable by edit).
     *
     * <p>Fail-closed: a controlled prescription cannot reach SIGNED or dispense
     * until two-factor verification is recorded, and the verification transport
     * is a pending product decision — flag with that understanding.
     */
    private Boolean controlledSubstance;

    /** Same contract as {@link #controlledSubstance}, for the co-sign gate. */
    private Boolean requiresCosign;

    /**
     * QA D660: required when a SIGNED prescription is cancelled or
     * discontinued, the only edit a signed order accepts.
     */
    @Size(max = 1000)
    private String withdrawalReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
