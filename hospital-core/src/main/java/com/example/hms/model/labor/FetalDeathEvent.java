package com.example.hms.model.labor;

import com.example.hms.enums.FetalDeathEventSource;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * QA D1714 (user decision 2026-11-13): a stillborn baby of a recorded delivery. It needs no patient account: it is
 * linked to the delivery, the mother and the baby's order, and it is what the mortality register counts as a
 * stillbirth. Written only by {@code FetalDeathEventService}. V293.
 *
 * <p>Recording is separate from qualifying: an event is recorded whatever the gestation. The named classifications
 * (WHO stillbirth indicator, perinatal, ICD level) are read from its facts on the register
 * ({@code StillbirthClassification}); a missing gestation reads "classification incomplete", never 0.
 *
 * <p>Never deleted. A correction of the baby's outcome supersedes it ({@link #supersededAt}, by whom, why); at most
 * one event per baby is not superseded (unique index {@code uq_fetal_death_live_per_baby}).
 */
@Entity
@Table(
    name = "fetal_death_events",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_fetal_death_delivery", columnList = "delivery_record_id"),
        @Index(name = "idx_fetal_death_hospital_time", columnList = "hospital_id, occurred_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"deliveryRecord", "motherPatient", "hospital", "recordedBy", "supersededBy"})
public class FetalDeathEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_record_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_fetal_death_delivery"))
    private DeliveryRecord deliveryRecord;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mother_patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_fetal_death_mother"))
    private Patient motherPatient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_fetal_death_hospital"))
    private Hospital hospital;

    /** The baby's order in the delivery: 1 for a single birth. */
    @Column(name = "birth_order", nullable = false)
    private int birthOrder;

    /** The delivery's birth date and time. */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** Completed weeks at delivery as recorded; null when not known (classification incomplete). */
    @Column(name = "gestational_age_weeks")
    private Integer gestationalAgeWeeks;

    /** A stillborn baby is recorded with no signs of life; set explicitly by the service, never by default. */
    @Column(name = "no_signs_of_life", nullable = false)
    private boolean noSignsOfLife;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private FetalDeathEventSource source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_user_id", foreignKey = @ForeignKey(name = "fk_fetal_death_recorded_by"))
    private User recordedBy;

    /** Set when the event was created by a correction (live to stillborn): the reason given. */
    @Column(name = "creation_reason", length = 500)
    private String creationReason;

    @Column(name = "superseded_at")
    private LocalDateTime supersededAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "superseded_by_user_id", foreignKey = @ForeignKey(name = "fk_fetal_death_superseded_by"))
    private User supersededBy;

    @Column(name = "supersede_reason", length = 500)
    private String supersedeReason;

    public boolean isSuperseded() {
        return supersededAt != null;
    }
}
