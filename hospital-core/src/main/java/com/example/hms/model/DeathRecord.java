package com.example.hms.model;

import com.example.hms.enums.MannerOfDeath;
import com.example.hms.enums.MaternalDeathTiming;
import com.example.hms.enums.PerinatalDeathType;
import com.example.hms.enums.PlaceOfDeath;
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
 * The death certificate (Tier 2 item 29).
 *
 * <p>One row per patient — a person dies once, and a unique index says so
 * rather than the service hoping so. {@code Patient.deceasedAt} is the flag
 * everything else reads; this is the account of what happened.
 *
 * <p><b>Cause of death is amendable.</b> An autopsy or a coroner routinely
 * revises it weeks later, so the record carries {@link #amendmentReason} and
 * {@link #amendedAt} instead of being frozen. What is NOT amendable is the fact
 * of death: there is no un-death path here, because reversing a death is a
 * data-correction exercise and not a workflow anyone should be able to trigger
 * from a form.
 */
@Entity
@Table(
    name = "death_records",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_death_hospital", columnList = "hospital_id, died_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"patient", "hospital", "certifiedBy", "recordedBy"})
public class DeathRecord extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_death_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_death_hospital"))
    private Hospital hospital;

    /** The encounter or admission during which the patient died, when known. */
    @Column(name = "encounter_id")
    private java.util.UUID encounterId;

    @Column(name = "admission_id")
    private java.util.UUID admissionId;

    @Column(name = "died_at", nullable = false)
    private LocalDateTime diedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "place_of_death", nullable = false, length = 30)
    @Builder.Default
    private PlaceOfDeath placeOfDeath = PlaceOfDeath.FACILITY;

    @Enumerated(EnumType.STRING)
    @Column(name = "manner_of_death", nullable = false, length = 30)
    @Builder.Default
    private MannerOfDeath mannerOfDeath = MannerOfDeath.NATURAL;

    /** What finally stopped the heart. */
    @Size(max = 500)
    @Column(name = "immediate_cause", nullable = false, length = 500)
    private String immediateCause;

    @Size(max = 20)
    @Column(name = "immediate_cause_code", length = 20)
    private String immediateCauseCode;

    /**
     * The disease that set the sequence going. This is the one that counts for
     * mortality statistics — an immediate cause of "cardiac arrest" tells the
     * register nothing on its own.
     */
    @Size(max = 500)
    @Column(name = "underlying_cause", length = 500)
    private String underlyingCause;

    @Size(max = 20)
    @Column(name = "underlying_cause_code", length = 20)
    private String underlyingCauseCode;

    @Size(max = 1000)
    @Column(name = "contributing_causes", length = 1000)
    private String contributingCauses;

    @Column(name = "maternal_death", nullable = false)
    @Builder.Default
    private Boolean maternalDeath = Boolean.FALSE;

    @Enumerated(EnumType.STRING)
    @Column(name = "maternal_death_timing", length = 30)
    private MaternalDeathTiming maternalDeathTiming;

    @Column(name = "perinatal_death", nullable = false)
    @Builder.Default
    private Boolean perinatalDeath = Boolean.FALSE;

    @Enumerated(EnumType.STRING)
    @Column(name = "perinatal_type", length = 30)
    private PerinatalDeathType perinatalType;

    @Column(name = "autopsy_requested", nullable = false)
    @Builder.Default
    private Boolean autopsyRequested = Boolean.FALSE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "certified_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_death_certifier"))
    private Staff certifiedBy;

    @Column(name = "certified_at")
    private LocalDateTime certifiedAt;

    @Column(name = "amended_at")
    private LocalDateTime amendedAt;

    @Size(max = 500)
    @Column(name = "amendment_reason", length = 500)
    private String amendmentReason;

    @Size(max = 1000)
    @Column(name = "notes", length = 1000)
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_death_recorder"))
    private Staff recordedBy;

    /**
     * A maternal death by the WHO definition — during pregnancy or within 42
     * days of its end.
     *
     * <p>LATE_MATERNAL is excluded on purpose: it falls outside that definition
     * and is reported separately, so counting it here would overstate the
     * facility's maternal mortality ratio.
     */
    public boolean isWhoMaternalDeath() {
        return Boolean.TRUE.equals(maternalDeath)
            && maternalDeathTiming != null
            && maternalDeathTiming != MaternalDeathTiming.LATE_MATERNAL;
    }

    public boolean isAmended() {
        return amendedAt != null;
    }
}
