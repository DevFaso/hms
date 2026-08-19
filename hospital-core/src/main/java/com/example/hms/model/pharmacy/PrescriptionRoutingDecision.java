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

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private RoutingDecisionStatus status = RoutingDecisionStatus.PENDING;

    @NotNull
    @Column(name = "decided_at", nullable = false)
    private LocalDateTime decidedAt;
}
