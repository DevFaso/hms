package com.example.hms.model.medication;

import com.example.hms.enums.MedicationCatalogChangeType;
import com.example.hms.enums.MedicationCatalogVersionKind;
import com.example.hms.enums.MedicationCatalogVersionStatus;
import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Go-live D2 (V385): one version of a medication catalogue entry, or of its dose limits, at one hospital.
 *
 * <p>A change to what prescribing and dispensing read is never written on the catalogue row: it is proposed here,
 * « en attente de validation », and reaches the row only when a second person with the right role approves it
 * ({@code MedicationCatalogVersionApplier}, the one writer). The rows of an entry are its history: who proposed
 * what, when and why, and who approved or refused it.
 *
 * <p>{@code hospitalId} null is a platform entry (the national list, shared by every hospital).
 * {@code catalogItemId} null is a proposed new entry that is not in the catalogue yet. Ids, not associations:
 * the audit after commit and the queue read these rows with no session open.
 *
 * <p>{@code payload} and {@code previousPayload} are JSON snapshots of catalogue configuration (a medicine's
 * names and limits), never patient data.
 */
@Entity
@Table(
    name = "medication_catalog_versions",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_med_catalog_version_hospital_status", columnList = "hospital_id, status"),
        @Index(name = "idx_med_catalog_version_item", columnList = "catalog_item_id, kind, version_number")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class MedicationCatalogVersion extends BaseEntity {

    @Column(name = "hospital_id")
    private UUID hospitalId;

    @Column(name = "catalog_item_id")
    private UUID catalogItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private MedicationCatalogVersionKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private MedicationCatalogChangeType changeType;

    /** 1, 2, 3… per entry and kind. A refused or withdrawn version keeps its number. */
    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private MedicationCatalogVersionStatus status;

    /** The entry as the queue names it (name, strength, form), as proposed. */
    @Column(name = "item_label", nullable = false, length = 700)
    private String itemLabel;

    /** The proposed values, JSON ({@code FormularySnapshot} or {@code DoseLimitSnapshot}). */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** The values in force when this version was proposed, JSON; null for a new entry. */
    @Column(name = "previous_payload", columnDefinition = "TEXT")
    private String previousPayload;

    /** Why the change is asked for, in the author's words. */
    @Column(name = "change_reason", nullable = false, length = 1000)
    private String changeReason;

    @Column(name = "proposed_by", nullable = false)
    private UUID proposedBy;

    @Column(name = "proposed_at", nullable = false)
    private LocalDateTime proposedAt;

    /** Who approved, refused or withdrew it. */
    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /**
     * On what authority it was approved or refused: a pharmacist, the médecin-chef, or the médecin-chef because
     * the hospital had no pharmacist. Null while it waits, and on a version its author withdrew.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "decision_basis", length = 40)
    private com.example.hms.enums.MedicationCatalogDecisionBasis decisionBasis;

    /** The reason of a refusal (required), or a note on an approval. */
    @Column(name = "decision_reason", length = 1000)
    private String decisionReason;
}
