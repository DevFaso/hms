package com.example.hms.model;

import com.example.hms.enums.MannerOfDeath;
import com.example.hms.enums.MaternalDeathTiming;
import com.example.hms.enums.PerinatalAgeBasis;
import com.example.hms.enums.PerinatalBirthStatus;
import com.example.hms.enums.PerinatalDeathType;
import com.example.hms.enums.PerinatalReportingEligibility;
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
 * of death. The one way back is QA D1488's: a hospital administrator marks a
 * record made on the wrong patient as {@link #enteredInErrorAt entered in error}.
 * The row is kept with who, when and why, and stops counting as a death.
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

    /** The state of this record a dispute read; moved by every amendment. */
    @jakarta.persistence.Version
    @Column(name = "version", nullable = false)
    private Long version;

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

    /** CR-08: the birth time the age at death was counted from, stated or found on the delivery record. */
    @Column(name = "born_at")
    private LocalDateTime bornAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "birth_status", nullable = false, length = 20)
    @Builder.Default
    private PerinatalBirthStatus birthStatus = PerinatalBirthStatus.UNKNOWN;

    /** Completed weeks; null is unknown, which leaves gestation-dependent reporting eligibility unknown. */
    @Column(name = "gestational_age_weeks")
    private Integer gestationalAgeWeeks;

    /**
     * QA D1714 (V293): the fetal-death event this stillbirth death record declares. The register counts that
     * stillbirth once, through the event, while the event is not superseded.
     */
    @Column(name = "fetal_death_event_id")
    private java.util.UUID fetalDeathEventId;

    /** Written when the perinatal classification is judged. A row from before that stays UNKNOWN. */
    @Enumerated(EnumType.STRING)
    @Column(name = "perinatal_age_basis", nullable = false, length = 20)
    @Builder.Default
    private PerinatalAgeBasis perinatalAgeBasis = PerinatalAgeBasis.UNKNOWN;

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

    /** QA D1488: when this row was marked entered in error. It is then no death, and leaves every count. */
    @Column(name = "entered_in_error_at")
    private LocalDateTime enteredInErrorAt;

    @Column(name = "entered_in_error_by_id")
    private java.util.UUID enteredInErrorById;

    @Size(max = 255)
    @Column(name = "entered_in_error_by_name", length = 255)
    private String enteredInErrorByName;

    @Size(max = 500)
    @Column(name = "entered_in_error_reason", length = 500)
    private String enteredInErrorReason;

    /**
     * QA D1516 (V281): what this death closed at its hospital, by kind ({@link #CLOSED_ADMISSIONS},
     * {@link #CLOSED_ENCOUNTERS}, {@link #CLOSED_APPOINTMENTS}), as ids, so marking it entered in error can flag
     * and name them. Null on a row written before V281.
     */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "closed_records", columnDefinition = "jsonb")
    private java.util.Map<String, java.util.List<String>> closedRecords;

    public static final String CLOSED_ADMISSIONS = "admissions";
    public static final String CLOSED_ENCOUNTERS = "encounters";
    public static final String CLOSED_APPOINTMENTS = "appointments";

    public boolean isEnteredInError() {
        return enteredInErrorAt != null;
    }

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

    /**
     * CR-07: the classification measured against WHO's perinatal definition, derived on read so that an amendment
     * cannot leave it stale. Null with no classification.
     *
     * <p>NOT CLINICALLY APPROVED: awaits the obstetric/neonatal lead and the responsible coding authority.
     */
    public PerinatalReportingEligibility getPerinatalEligibility() {
        if (!Boolean.TRUE.equals(perinatalDeath) || perinatalType == null) {
            return null;
        }
        if (!perinatalType.inWhoPerinatalPeriod() || isBelowWhoPerinatalGestation()) {
            return PerinatalReportingEligibility.NOT_ELIGIBLE;
        }
        return gestationalAgeWeeks != null && birthFactsSupportTheType()
            ? PerinatalReportingEligibility.ELIGIBLE : PerinatalReportingEligibility.UNKNOWN;
    }

    /** A known gestation under WHO's start. Unknown gestation is not below it. */
    public boolean isBelowWhoPerinatalGestation() {
        return gestationalAgeWeeks != null && gestationalAgeWeeks < PerinatalDeathType.WHO_PERINATAL_MIN_GESTATION_WEEKS;
    }

    /**
     * The WHO perinatal numerator: stillbirths and early neonatal deaths, without a late neonatal death or a known
     * gestation under the start. Missing facts keep a death in, shown as unconfirmed: the clinician classified it.
     */
    public boolean isInWhoPerinatalNumerator() {
        PerinatalReportingEligibility eligibility = getPerinatalEligibility();
        return eligibility != null && eligibility != PerinatalReportingEligibility.NOT_ELIGIBLE;
    }

    private boolean birthFactsSupportTheType() {
        PerinatalBirthStatus expected = perinatalType == PerinatalDeathType.STILLBIRTH
            ? PerinatalBirthStatus.STILLBIRTH : PerinatalBirthStatus.LIVE_BIRTH;
        return birthStatus == expected
            && (perinatalAgeBasis == PerinatalAgeBasis.ELAPSED_TIME || perinatalAgeBasis == PerinatalAgeBasis.DATE_ONLY);
    }

    public boolean isAmended() {
        return amendedAt != null;
    }
}
