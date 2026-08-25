package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * The verdict on one (request, unit) pair (Tier 2 item 28).
 *
 * <p>{@link #compatible} is the lab's serologic finding, but it is not taken on
 * trust: the service re-checks ABO and Rh against
 * {@code AboGroup.isCompatible} and REFUSES to record a compatible verdict for
 * a pair those rules reject. A tick box cannot override the antigen biology,
 * and an ABO-incompatible red cell transfusion is the never-event this whole
 * module exists to prevent.
 *
 * <p>A crossmatch expires. The reservation it creates is not indefinite — a
 * unit held for a patient who never received it must return to stock rather
 * than sit committed forever.
 */
@Entity
@Table(
    name = "transfusion_crossmatches",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_crossmatch_hospital", columnList = "hospital_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"request", "bloodUnit", "hospital", "performedBy"})
public class TransfusionCrossmatch extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_crossmatch_request"))
    private TransfusionRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blood_unit_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_crossmatch_unit"))
    private BloodUnit bloodUnit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_crossmatch_hospital"))
    private Hospital hospital;

    @Column(name = "compatible", nullable = false)
    private Boolean compatible;

    @Size(max = 60)
    @Column(name = "method", length = 60)
    private String method;

    @Size(max = 500)
    @Column(name = "incompatibility_reason", length = 500)
    private String incompatibilityReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_crossmatch_staff"))
    private Staff performedBy;

    @Column(name = "performed_at", nullable = false)
    private LocalDateTime performedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** Compatible, and the reservation has not lapsed. */
    public boolean isUsableAt(LocalDateTime now) {
        return Boolean.TRUE.equals(compatible)
            && (expiresAt == null || expiresAt.isAfter(now));
    }
}
