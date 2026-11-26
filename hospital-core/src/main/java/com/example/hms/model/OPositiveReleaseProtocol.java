package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

/**
 * The emergency O RhD positive protocol of one hospital (V380, rule set EMERGENCY_O_POSITIVE_AUTHORITY). No
 * row, or an inactive one, means the hospital wrote no protocol: every release is then handled as high-risk.
 */
@Entity
@Table(
    name = "o_positive_release_protocols",
    schema = "hospital",
    uniqueConstraints = @UniqueConstraint(name = "uq_o_positive_protocol_hospital", columnNames = "hospital_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = "hospital")
public class OPositiveReleaseProtocol extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_o_positive_protocol_hospital"))
    private Hospital hospital;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = false;

    /** A woman above this age is in the low-risk tier; at or below it she is protected. */
    @Column(name = "female_age_cutoff", nullable = false)
    @Builder.Default
    private int femaleAgeCutoff = 50;

    /** Whether a boy under 18 is in the low-risk tier (as in France) or protected (the default). */
    @Column(name = "boys_low_risk", nullable = false)
    @Builder.Default
    private boolean boysLowRisk = false;

    /** The deadline of the countersignature and of the urgent review, in hours. */
    @Column(name = "review_deadline_hours", nullable = false)
    @Builder.Default
    private int reviewDeadlineHours = 24;

    @Column(name = "updated_by_user_id")
    private UUID updatedByUserId;
}
