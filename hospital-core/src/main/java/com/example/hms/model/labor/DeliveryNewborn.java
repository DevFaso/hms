package com.example.hms.model.labor;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

/**
 * QA D1592: a live-born baby of a recorded delivery, registered as its own
 * patient (V289). Written only by {@code NewbornRegistrationService}. A row is
 * never deleted: when the delivery's live-birth count later disagrees with the
 * babies registered, the extra rows are flagged {@link #needsReview} and a
 * person decides; the baby's patient record stays.
 */
@Entity
@Table(
    name = "delivery_newborns",
    schema = "clinical",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_delivery_newborn_order", columnNames = {"delivery_record_id", "birth_order"}),
        @UniqueConstraint(name = "uk_delivery_newborn_patient", columnNames = {"newborn_patient_id"})
    },
    indexes = {
        @Index(name = "idx_delivery_newborn_mother", columnList = "mother_patient_id"),
        @Index(name = "idx_delivery_newborn_hospital", columnList = "hospital_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"deliveryRecord", "newbornPatient", "motherPatient", "hospital"})
public class DeliveryNewborn extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_record_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_delivery_newborn_delivery"))
    private DeliveryRecord deliveryRecord;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "newborn_patient_id", nullable = false, unique = true,
        foreignKey = @ForeignKey(name = "fk_delivery_newborn_patient"))
    private Patient newbornPatient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mother_patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_delivery_newborn_mother"))
    private Patient motherPatient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_delivery_newborn_hospital"))
    private Hospital hospital;

    /** 1 for a single birth; 1, 2, ... for a multiple birth, in the order recorded. */
    @Column(name = "birth_order", nullable = false)
    private int birthOrder;

    @Column(name = "needs_review", nullable = false)
    @Builder.Default
    private boolean needsReview = false;

    @Column(name = "review_reason", length = 255)
    private String reviewReason;
}
