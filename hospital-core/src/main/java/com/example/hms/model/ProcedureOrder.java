package com.example.hms.model;

import com.example.hms.enums.ProcedureOrderStatus;
import com.example.hms.enums.ProcedureUrgency;
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

import java.time.LocalDateTime;

@Entity
@Table(
    name = "procedure_orders",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_procedure_order_patient", columnList = "patient_id"),
        @Index(name = "idx_procedure_order_hospital", columnList = "hospital_id"),
        @Index(name = "idx_procedure_order_ordering", columnList = "ordering_provider_id"),
        @Index(name = "idx_procedure_order_status", columnList = "status"),
        @Index(name = "idx_procedure_order_scheduled", columnList = "scheduled_datetime")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class ProcedureOrder extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_procedure_order_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_procedure_order_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ordering_provider_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_procedure_order_ordering_provider"))
    private Staff orderingProvider;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encounter_id",
        foreignKey = @ForeignKey(name = "fk_procedure_order_encounter"))
    private Encounter encounter;

    @Column(name = "procedure_code", length = 50)
    private String procedureCode;

    @Column(name = "procedure_name", nullable = false, length = 255)
    private String procedureName;

    @Column(name = "procedure_category", length = 100)
    private String procedureCategory;

    @Column(name = "indication", nullable = false, columnDefinition = "TEXT")
    private String indication;

    @Column(name = "clinical_notes", columnDefinition = "TEXT")
    private String clinicalNotes;

    @Enumerated(EnumType.STRING)
    @Column(name = "urgency", nullable = false, length = 30)
    private ProcedureUrgency urgency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private ProcedureOrderStatus status = ProcedureOrderStatus.ORDERED;

    @Column(name = "scheduled_datetime")
    private LocalDateTime scheduledDatetime;

    @Column(name = "estimated_duration_minutes")
    private Integer estimatedDurationMinutes;

    @Column(name = "requires_anesthesia")
    @Builder.Default
    private Boolean requiresAnesthesia = Boolean.FALSE;

    @Column(name = "anesthesia_type", length = 50)
    private String anesthesiaType;

    @Column(name = "requires_sedation")
    @Builder.Default
    private Boolean requiresSedation = Boolean.FALSE;

    @Column(name = "sedation_type", length = 50)
    private String sedationType;

    @Column(name = "pre_procedure_instructions", columnDefinition = "TEXT")
    private String preProcedureInstructions;

    @Column(name = "consent_obtained")
    @Builder.Default
    private Boolean consentObtained = Boolean.FALSE;

    @Column(name = "consent_obtained_at")
    private LocalDateTime consentObtainedAt;

    @Column(name = "consent_obtained_by")
    private String consentObtainedBy;

    @Column(name = "consent_form_location", length = 500)
    private String consentFormLocation;

    @Column(name = "laterality", length = 20)
    private String laterality;

    @Column(name = "site_marked")
    @Builder.Default
    private Boolean siteMarked = Boolean.FALSE;

    @Column(name = "special_equipment_needed", columnDefinition = "TEXT")
    private String specialEquipmentNeeded;

    @Column(name = "blood_products_required")
    @Builder.Default
    private Boolean bloodProductsRequired = Boolean.FALSE;

    @Column(name = "imaging_guidance_required")
    @Builder.Default
    private Boolean imagingGuidanceRequired = Boolean.FALSE;

    @Column(name = "ordered_at", nullable = false)
    @Builder.Default
    private LocalDateTime orderedAt = LocalDateTime.now();

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
