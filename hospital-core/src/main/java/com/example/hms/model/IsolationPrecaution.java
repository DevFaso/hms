package com.example.hms.model;

import com.example.hms.enums.IsolationPrecautionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * An isolation precaution in force for a patient (Tier 2 item 32).
 *
 * <p>A child row rather than a column on the admission, because concurrent
 * precautions are normal: a viral haemorrhagic fever is contact AND droplet,
 * and a neutropenic patient on protective isolation may also be on contact
 * precautions for a colonising organism. One enum column would force somebody
 * to choose which risk to under-communicate.
 *
 * <p><b>Active means {@link #endedAt} is null.</b> Precautions are
 * discontinued, never deleted — "was this patient on airborne precautions
 * last Tuesday" is exactly the question contact tracing asks, and a deleted
 * row cannot answer it.
 *
 * <p>{@link #admission} is deliberately nullable. Precautions start in the
 * emergency department before anyone is admitted, which is when they matter
 * most: the decision they drive is which bed the patient may be given.
 */
@Entity
@Table(
    name = "isolation_precautions",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_isolation_by_admission", columnList = "admission_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"patient", "hospital", "admission", "orderedBy", "discontinuedBy"})
public class IsolationPrecaution extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_isolation_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_isolation_patient"))
    private Patient patient;

    /** Null while the patient is not admitted — an ED precaution is still a precaution. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admission_id",
        foreignKey = @ForeignKey(name = "fk_isolation_admission"))
    private Admission admission;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "precaution_type", nullable = false, length = 20)
    private IsolationPrecautionType precautionType;

    @NotNull
    @Size(max = 500)
    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    /**
     * Kept apart from {@link #reason} because the organism drives the
     * ward-compatibility rule while the reason is what a clinician reads.
     */
    @Size(max = 120)
    @Column(name = "suspected_organism", length = 120)
    private String suspectedOrganism;

    @NotNull
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ordered_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_isolation_ordered_by"))
    private Staff orderedBy;

    /** Null = still in force. Every read in the hot path filters on this. */
    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discontinued_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_isolation_discontinued_by"))
    private Staff discontinuedBy;

    @Size(max = 500)
    @Column(name = "discontinuation_reason", length = 500)
    private String discontinuationReason;

    @Size(max = 1000)
    @Column(name = "notes", length = 1000)
    private String notes;

    /** In force right now. */
    public boolean isActive() {
        return endedAt == null;
    }

    /**
     * Whether this precaution constrains bed placement. Only airborne does —
     * see {@link IsolationPrecautionType#requiresIsolationWard()}.
     */
    public boolean requiresIsolationWard() {
        return isActive() && precautionType != null && precautionType.requiresIsolationWard();
    }
}
