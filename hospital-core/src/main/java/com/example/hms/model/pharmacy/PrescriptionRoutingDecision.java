package com.example.hms.model.pharmacy;

import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "prescription_routing_decisions",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_routing_prescription", columnList = "prescription_id"),
        @Index(name = "idx_routing_patient", columnList = "decided_for_patient_id"),
        @Index(name = "idx_routing_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"prescription", "targetPharmacy", "decidedByUser", "decidedForPatient"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PrescriptionRoutingDecision extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prescription_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_routing_prescription"))
    private Prescription prescription;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "routing_type", nullable = false, length = 20)
    private RoutingType routingType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_pharmacy_id",
        foreignKey = @ForeignKey(name = "fk_routing_target_pharmacy"))
    private Pharmacy targetPharmacy;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "decided_by_user_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_routing_decided_by"))
    private User decidedByUser;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "decided_for_patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_routing_patient"))
    private Patient decidedForPatient;

    @Size(max = 1024)
    @Column(name = "reason", length = 1024)
    private String reason;

    @Column(name = "estimated_restock_date")
    private LocalDate estimatedRestockDate;

    /**
     * What this decision is for: the quantity still owed when it was taken
     * (prescribed lifetime minus dispensed), not the full prescribed amount.
     * Null when the prescription carries no quantity, or on rows written
     * before V162 — both mean "unknown", never "nothing left".
     */
    @Column(name = "remaining_quantity", precision = 12, scale = 2)
    private java.math.BigDecimal remainingQuantity;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private RoutingDecisionStatus status = RoutingDecisionStatus.PENDING;

    @NotNull
    @Column(name = "decided_at", nullable = false)
    private LocalDateTime decidedAt;

    /**
     * The partner accepted this order and never delivered it (V167).
     *
     * <p>A fact of its own rather than a phrase in {@link #reason}: that column
     * is free text a pharmacist types into, so a phrase stored there could not
     * be told apart from somebody's sentence, and an English phrase reached
     * French and Spanish prescribers untranslated. The client renders the fact
     * in the reader's language from this flag.
     */
    @Column(name = "partner_no_show", nullable = false)
    @Builder.Default
    private boolean partnerNoShow = false;

    /** The pharmacist's own words when recording the no-show, exactly as typed. */
    @Column(name = "no_show_reason", length = 1024)
    private String noShowReason;
}
