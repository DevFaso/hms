package com.example.hms.model;

import com.example.hms.enums.LabOrderChannel;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.model.converter.DiagnosisCodesConverter;
import com.example.hms.utility.DiagnosisCodeValidator;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "lab_orders",
    schema = "lab",
    indexes = {
        @Index(name = "idx_lab_order_patient", columnList = "patient_id"),
        @Index(name = "idx_lab_order_staff", columnList = "ordering_staff_id"),
        @Index(name = "idx_lab_order_hospital", columnList = "hospital_id"),
        @Index(name = "idx_lab_order_status", columnList = "status"),
        @Index(name = "idx_lab_order_datetime", columnList = "order_datetime")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"patient", "orderingStaff", "encounter", "labTestDefinition", "assignment", "hospital"})
@EqualsAndHashCode(callSuper = true)
public class LabOrder extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_laborder_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ordering_staff_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_laborder_staff"))
    private Staff orderingStaff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encounter_id",
        foreignKey = @ForeignKey(name = "fk_laborder_encounter"))
    private Encounter encounter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lab_test_definition_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_laborder_testdef"))
    private LabTestDefinition labTestDefinition;

    @NotNull
    @Column(name = "order_datetime", nullable = false)
    private LocalDateTime orderDatetime;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private LabOrderStatus status;

    @NotBlank
    @Column(name = "clinical_indication", nullable = false, length = 2048)
    private String clinicalIndication;

    @Column(name = "medical_necessity_note", length = 2048)
    private String medicalNecessityNote;

    @Column(length = 2048)
    private String notes;

    @Column(name = "primary_diagnosis_code", length = 20)
    private String primaryDiagnosisCode;

    @Convert(converter = DiagnosisCodesConverter.class)
    @Column(name = "additional_diagnosis_codes", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> additionalDiagnosisCodes = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "order_channel", nullable = false, length = 32)
    private LabOrderChannel orderChannel;

    @Column(name = "order_channel_other", length = 120)
    private String orderChannelOther;

    @Builder.Default
    @Column(name = "documentation_shared_with_lab", nullable = false)
    private boolean documentationSharedWithLab = false;

    @Column(name = "documentation_reference", length = 255)
    private String documentationReference;

    @Column(name = "ordering_provider_npi", length = 20)
    private String orderingProviderNpi;

    @Column(name = "provider_signature_digest", length = 512)
    private String providerSignatureDigest;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "signed_by_user_id")
    private UUID signedByUserId;

    @Builder.Default
    @Column(name = "standing_order", nullable = false)
    private boolean standingOrder = false;

    @Column(name = "standing_order_expires_at")
    private LocalDateTime standingOrderExpiresAt;

    @Column(name = "standing_order_last_reviewed_at")
    private LocalDateTime standingOrderLastReviewedAt;

    @Column(name = "standing_order_review_due_at")
    private LocalDateTime standingOrderReviewDueAt;

    @Column(name = "standing_order_review_interval_days")
    private Integer standingOrderReviewIntervalDays;

    @Column(name = "standing_order_review_notes", length = 2048)
    private String standingOrderReviewNotes;

    /** Context (role@hospital) of the ordering user. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_laborder_assignment"))
    private UserRoleHospitalAssignment assignment;

    /** Redundant but useful for querying and uniqueness. Must match assignment.hospital. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_laborder_hospital"))
    private Hospital hospital;

    @PrePersist
    @PreUpdate
    private void validate() {
        ensureOrderDefaults();
        validateHospitalScope();
        validateEncounterConsistency();
        validateComplianceMetadata();
    }

    private void ensureOrderDefaults() {
        if (orderDatetime == null) {
            orderDatetime = LocalDateTime.now();
        }
        if (status == null) {
            status = LabOrderStatus.ORDERED;
        }
        if (orderChannel == null) {
            orderChannel = LabOrderChannel.ELECTRONIC;
        }
        normalizeClinicalDetails();
        if (additionalDiagnosisCodes == null) {
            additionalDiagnosisCodes = new ArrayList<>();
        }
    }

    private void validateHospitalScope() {
        if (assignment == null || assignment.getHospital() == null || hospital == null
            || !Objects.equals(assignment.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("LabOrder.assignment.hospital must match LabOrder.hospital");
        }
        if (orderingStaff == null || orderingStaff.getHospital() == null
            || !Objects.equals(orderingStaff.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("Ordering staff must belong to LabOrder.hospital");
        }
        if (labTestDefinition == null) {
            throw new IllegalStateException("Lab test definition must be provided");
        }
        if (labTestDefinition.getHospital() != null
            && !Objects.equals(labTestDefinition.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("Lab test definition must belong to LabOrder.hospital");
        }
    }

    private void validateEncounterConsistency() {
        if (encounter == null) {
            return;
        }
        if (!Objects.equals(encounter.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("Encounter.hospital must match LabOrder.hospital");
        }
        if (!Objects.equals(encounter.getPatient().getId(), patient.getId())) {
            throw new IllegalStateException("Encounter.patient must match LabOrder.patient");
        }
        if (!Objects.equals(encounter.getStaff().getId(), orderingStaff.getId())) {
            throw new IllegalStateException("Encounter.staff must match orderingStaff");
        }
    }

    private void normalizeClinicalDetails() {
        if (clinicalIndication != null) {
            clinicalIndication = clinicalIndication.trim();
        }
        if (clinicalIndication == null || clinicalIndication.isBlank()) {
            throw new IllegalStateException("Clinical indication must be provided");
        }
        if (medicalNecessityNote != null) {
            medicalNecessityNote = medicalNecessityNote.trim();
            if (medicalNecessityNote.isBlank()) {
                medicalNecessityNote = null;
            }
        }
        if (primaryDiagnosisCode != null) {
            primaryDiagnosisCode = DiagnosisCodeValidator.normalize(primaryDiagnosisCode);
        }
        if (additionalDiagnosisCodes == null) {
            additionalDiagnosisCodes = new ArrayList<>();
        }
    }

    private void validateComplianceMetadata() {
        validateOrderChannelDetails();
        validateStandingOrderPolicies();
    }

    private void validateOrderChannelDetails() {
        if (orderChannel == LabOrderChannel.OTHER) {
            if (orderChannelOther == null || orderChannelOther.isBlank()) {
                throw new IllegalStateException("orderChannelOther must be provided when orderChannel is OTHER");
            }
            orderChannelOther = orderChannelOther.trim();
        } else {
            orderChannelOther = orderChannelOther != null && !orderChannelOther.isBlank() ? orderChannelOther.trim() : null;
        }
        if (!documentationSharedWithLab) {
            documentationReference = null;
        } else if (documentationReference != null) {
            documentationReference = documentationReference.trim();
            if (documentationReference.isBlank()) {
                documentationReference = null;
            }
        }
    }

    private void validateStandingOrderPolicies() {
        if (!standingOrder) {
            standingOrderExpiresAt = null;
            standingOrderLastReviewedAt = null;
            standingOrderReviewDueAt = null;
            standingOrderReviewIntervalDays = null;
            standingOrderReviewNotes = null;
            return;
        }
        if (standingOrderExpiresAt == null) {
            throw new IllegalStateException("Standing orders must define an expiration timestamp");
        }
        if (standingOrderLastReviewedAt == null || standingOrderReviewIntervalDays == null) {
            throw new IllegalStateException("Standing orders require lastReviewedAt and review interval");
        }
        if (standingOrderReviewIntervalDays <= 0) {
            throw new IllegalStateException("Standing order review interval must be positive");
        }
        if (standingOrderReviewDueAt == null) {
            standingOrderReviewDueAt = standingOrderLastReviewedAt.plusDays(standingOrderReviewIntervalDays);
        }
    }
}
