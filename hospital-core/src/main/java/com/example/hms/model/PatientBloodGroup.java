package com.example.hms.model;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.AntibodyScreenResult;
import com.example.hms.enums.RhFactor;
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
 * A lab-verified type and screen (Tier 2 item 28).
 *
 * <p>Deliberately NOT {@code Patient.bloodType}, which is free text captured at
 * the registration desk. You do not transfuse on a patient-reported blood type;
 * this row is the coded, performed, attributable one.
 *
 * <p>ABO and Rh are lifelong facts; the antibody screen is not. A patient who
 * has been transfused or pregnant since the last screen can have developed new
 * antibodies, so {@link #expiresAt} governs whether the screen may still be
 * used for a crossmatch. The ABO/Rh on an expired row stays usable — losing the
 * group because the screen went stale would be worse than useless in a
 * haemorrhage.
 *
 * <p>Repeat testing SUPERSEDES rather than overwrites: a partial unique index
 * keeps exactly one non-superseded row per patient per hospital and the history
 * stays readable (the deactivate-never-delete stance used for guarantors and
 * advance directives).
 */
@Entity
@Table(
    name = "patient_blood_groups",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_blood_group_patient",  columnList = "patient_id"),
        @Index(name = "idx_blood_group_hospital", columnList = "hospital_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"patient", "hospital", "performedBy"})
public class PatientBloodGroup extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_blood_group_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_blood_group_hospital"))
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(name = "abo_group", nullable = false, length = 3)
    private AboGroup aboGroup;

    @Enumerated(EnumType.STRING)
    @Column(name = "rh_factor", nullable = false, length = 10)
    private RhFactor rhFactor;

    @Enumerated(EnumType.STRING)
    @Column(name = "antibody_screen", nullable = false, length = 20)
    @Builder.Default
    private AntibodyScreenResult antibodyScreen = AntibodyScreenResult.NOT_DONE;

    @Size(max = 500)
    @Column(name = "antibody_detail", length = 500)
    private String antibodyDetail;

    @Column(name = "specimen_collected_at")
    private LocalDateTime specimenCollectedAt;

    @Column(name = "performed_at", nullable = false)
    private LocalDateTime performedAt;

    /** When the ANTIBODY SCREEN goes stale. Null means no expiry was recorded. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_blood_group_performer"))
    private Staff performedBy;

    @Column(name = "superseded", nullable = false)
    @Builder.Default
    private Boolean superseded = Boolean.FALSE;

    @Size(max = 1000)
    @Column(name = "notes", length = 1000)
    private String notes;

    /**
     * True when the screen is still usable for a crossmatch.
     *
     * <p>NOT_DONE counts as not current: a missing screen and a negative screen
     * are different facts, and treating the first as the second is how an
     * alloantibody gets missed.
     */
    public boolean screenIsCurrent(LocalDateTime now) {
        return antibodyScreen != null
            && antibodyScreen != AntibodyScreenResult.NOT_DONE
            && (expiresAt == null || expiresAt.isAfter(now));
    }
}
