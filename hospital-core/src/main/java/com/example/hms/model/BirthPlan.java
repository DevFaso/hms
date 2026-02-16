package com.example.hms.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Birth Plan entity for documenting pregnant patients' labor and delivery preferences.
 * Supports structured preference capture with provider review workflow.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "birth_plans",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_birth_plans_patient", columnList = "patient_id"),
        @Index(name = "idx_birth_plans_hospital", columnList = "hospital_id"),
        @Index(name = "idx_birth_plans_created", columnList = "created_at")
    }
)
public class BirthPlan extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "patient_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_birth_plans_patient")
    )
    private Patient patient;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "hospital_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_birth_plans_hospital")
    )
    private Hospital hospital;

    // Introduction Section
    @NotBlank
    @Size(max = 255)
    @Column(name = "patient_name", nullable = false)
    private String patientName;

    @NotNull
    @Column(name = "expected_due_date", nullable = false)
    private LocalDate expectedDueDate;

    @Size(max = 255)
    @Column(name = "place_of_birth")
    private String placeOfBirth;

    @Size(max = 255)
    @Column(name = "healthcare_provider")
    private String healthcareProvider;

    @Column(name = "medical_conditions", columnDefinition = "TEXT")
    private String medicalConditions;

    // Delivery Preferences Section
    @Size(max = 50)
    @Column(name = "preferred_delivery_method")
    private String preferredDeliveryMethod;

    @Size(max = 50)
    @Column(name = "backup_delivery_method")
    private String backupDeliveryMethod;

    @Column(name = "delivery_method_notes", columnDefinition = "TEXT")
    private String deliveryMethodNotes;

    // Pain Management Section
    @Size(max = 50)
    @Column(name = "preferred_pain_approach")
    private String preferredPainApproach;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "birth_plan_unmedicated_techniques",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "birth_plan_id"),
        foreignKey = @ForeignKey(name = "fk_unmedicated_techniques_birth_plan")
    )
    @Column(name = "technique")
    @OrderColumn(name = "technique_order")
    @Builder.Default
    private List<String> unmedicatedTechniques = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "birth_plan_medicated_options",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "birth_plan_id"),
        foreignKey = @ForeignKey(name = "fk_medicated_options_birth_plan")
    )
    @Column(name = "option")
    @OrderColumn(name = "option_order")
    @Builder.Default
    private List<String> medicatedOptions = new ArrayList<>();

    @Column(name = "pain_management_notes", columnDefinition = "TEXT")
    private String painManagementNotes;

    // Delivery Room Environment Section
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "birth_plan_support_persons",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "birth_plan_id"),
        foreignKey = @ForeignKey(name = "fk_support_persons_birth_plan")
    )
    @Column(name = "person_name")
    @OrderColumn(name = "person_order")
    @Builder.Default
    private List<String> supportPersons = new ArrayList<>();

    @Size(max = 100)
    @Column(name = "lighting_preference")
    private String lightingPreference;

    @Size(max = 255)
    @Column(name = "music_preference")
    private String musicPreference;

    @Size(max = 50)
    @Column(name = "fetal_monitoring_style")
    private String fetalMonitoringStyle;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "birth_plan_comfort_items",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "birth_plan_id"),
        foreignKey = @ForeignKey(name = "fk_comfort_items_birth_plan")
    )
    @Column(name = "item")
    @OrderColumn(name = "item_order")
    @Builder.Default
    private List<String> comfortItems = new ArrayList<>();

    @Column(name = "movement_during_labor")
    private Boolean movementDuringLabor;

    @Column(name = "environment_notes", columnDefinition = "TEXT")
    private String environmentNotes;

    // Postpartum Preferences Section
    @Column(name = "delayed_cord_clamping")
    private Boolean delayedCordClamping;

    @Column(name = "cord_clamping_duration")
    private Integer cordClampingDuration;

    @Size(max = 100)
    @Column(name = "who_cuts_cord")
    private String whoCutsCord;

    @Column(name = "skin_to_skin_contact")
    private Boolean skinToSkinContact;

    @Size(max = 20)
    @Column(name = "vitamin_k_shot")
    private String vitaminKShot;

    @Size(max = 20)
    @Column(name = "eye_ointment")
    private String eyeOintment;

    @Size(max = 20)
    @Column(name = "hepatitis_b_vaccine")
    private String hepatitisBVaccine;

    @Size(max = 50)
    @Column(name = "first_bath_timing")
    private String firstBathTiming;

    @Size(max = 50)
    @Column(name = "feeding_method")
    private String feedingMethod;

    @Column(name = "postpartum_notes", columnDefinition = "TEXT")
    private String postpartumNotes;

    // Additional preferences and acknowledgments
    @Column(name = "additional_wishes", columnDefinition = "TEXT")
    private String additionalWishes;

    @NotNull
    @Column(name = "flexibility_acknowledgment", nullable = false)
    private Boolean flexibilityAcknowledgment;

    @Column(name = "discussed_with_provider")
    private Boolean discussedWithProvider;

    @Column(name = "provider_signature")
    private String providerSignature;

    @Column(name = "provider_signature_date")
    private LocalDateTime providerSignatureDate;

    @Column(name = "provider_comments", columnDefinition = "TEXT")
    private String providerComments;

    // Status and Review Section
    @Column(name = "provider_review_required", nullable = false)
    @Builder.Default
    private Boolean providerReviewRequired = Boolean.TRUE;

    @Column(name = "provider_reviewed", nullable = false)
    @Builder.Default
    private Boolean providerReviewed = Boolean.FALSE;

    @Column(name = "reviewed_by_provider_id")
    private Long reviewedByProviderId;

    @Column(name = "review_timestamp")
    private LocalDateTime reviewTimestamp;

    // boolean-style getters used by tests
    public boolean isProviderReviewRequired() {
        return Boolean.TRUE.equals(this.providerReviewRequired);
    }

    public boolean isProviderReviewed() {
        return Boolean.TRUE.equals(this.providerReviewed);
    }

}
