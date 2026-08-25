package com.example.hms.model;

import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.TransfusionRequestStatus;
import com.example.hms.enums.TransfusionUrgency;
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

/** A clinician's request for blood components (Tier 2 item 28). */
@Entity
@Table(
    name = "transfusion_requests",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_transfusion_req_patient",  columnList = "patient_id"),
        @Index(name = "idx_transfusion_req_hospital", columnList = "hospital_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"patient", "hospital", "encounter", "bloodGroup", "requestedBy"})
public class TransfusionRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_transfusion_req_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_transfusion_req_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encounter_id",
        foreignKey = @ForeignKey(name = "fk_transfusion_req_encounter"))
    private Encounter encounter;

    /**
     * The type and screen this request was raised against. Null on an emergency
     * release, where there was no time to type the patient — {@link #urgency}
     * carries that story rather than leaving an unexplained gap.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blood_group_id",
        foreignKey = @ForeignKey(name = "fk_transfusion_req_group"))
    private PatientBloodGroup bloodGroup;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false, length = 30)
    private BloodProductType productType;

    @Column(name = "units_requested", nullable = false)
    private Integer unitsRequested;

    @Size(max = 500)
    @Column(name = "indication", nullable = false, length = 500)
    private String indication;

    @Enumerated(EnumType.STRING)
    @Column(name = "urgency", nullable = false, length = 20)
    @Builder.Default
    private TransfusionUrgency urgency = TransfusionUrgency.ROUTINE;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private TransfusionRequestStatus status = TransfusionRequestStatus.REQUESTED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_transfusion_req_staff"))
    private Staff requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "required_by")
    private LocalDateTime requiredBy;

    @Size(max = 500)
    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Size(max = 1000)
    @Column(name = "notes", length = 1000)
    private String notes;

    public boolean isTerminal() {
        return status == TransfusionRequestStatus.COMPLETED
            || status == TransfusionRequestStatus.CANCELLED;
    }

    public boolean isEmergency() {
        return urgency == TransfusionUrgency.EMERGENCY;
    }
}
