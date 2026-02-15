package com.example.hms.model;

import com.example.hms.enums.RefillStatus;
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

/**
 * Patient-initiated medication refill request — tracks the lifecycle
 * from REQUESTED → APPROVED/DENIED → DISPENSED.
 * <p>
 * Linked to an existing {@link Prescription} and the requesting {@link Patient}.
 */
@Entity
@Table(
    name = "refill_requests",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_refill_patient",      columnList = "patient_id"),
        @Index(name = "idx_refill_prescription",  columnList = "prescription_id"),
        @Index(name = "idx_refill_status",        columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"patient", "prescription"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class RefillRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_refill_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prescription_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_refill_prescription"))
    private Prescription prescription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RefillStatus status = RefillStatus.REQUESTED;

    @Size(max = 500)
    @Column(name = "preferred_pharmacy", length = 500)
    private String preferredPharmacy;

    @Size(max = 1000)
    @Column(name = "patient_notes", length = 1000)
    private String patientNotes;

    @Size(max = 1000)
    @Column(name = "provider_notes", length = 1000)
    private String providerNotes;
}
