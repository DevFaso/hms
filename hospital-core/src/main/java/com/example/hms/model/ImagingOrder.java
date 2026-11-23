package com.example.hms.model;

import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.enums.ImagingLaterality;
import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderPriority;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.tenant.TenantEntityListener;
import com.example.hms.security.tenant.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity representing a generalized imaging/radiology order (XR, CT, MRI, etc.).
 */
@Entity
@Table(
    name = "imaging_orders",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_imaging_order_patient", columnList = "patient_id"),
        @Index(name = "idx_imaging_order_hospital", columnList = "hospital_id"),
        @Index(name = "idx_imaging_order_modality", columnList = "modality"),
        @Index(name = "idx_imaging_order_status", columnList = "status"),
        @Index(name = "idx_imaging_order_body_region", columnList = "body_region"),
        @Index(name = "idx_imaging_order_scheduled_date", columnList = "scheduled_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@EntityListeners(TenantEntityListener.class)
@ToString(exclude = {"patient", "hospital"})
public class ImagingOrder extends BaseEntity implements TenantScoped {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "modality", nullable = false, length = 40)
    private ImagingModality modality;

    @NotNull
    @Column(name = "study_type", nullable = false, length = 150)
    private String studyType;

    /**
     * QA D3450 / D3254 (r70, V361): the exam of the hospital's imaging catalogue (a price-list item of the IMAGING
     * category) this order was picked from; null for an order typed in free text. The order is priced by this
     * item, and a family proxy's read is decided by the item's pregnancy flag. Set only by
     * {@code ImagingCatalogueService}, at the order's own hospital.
     */
    @Column(name = "catalogue_item_id")
    private UUID catalogueItemId;

    /** The catalogue item's code when the order was placed (a copy, for reading). */
    @Column(name = "catalogue_code", length = 40)
    private String catalogueCode;

    @Column(name = "body_region", length = 150)
    private String bodyRegion;

    @Enumerated(EnumType.STRING)
    @Column(name = "laterality", length = 30)
    private ImagingLaterality laterality;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    @Builder.Default
    private ImagingOrderPriority priority = ImagingOrderPriority.ROUTINE;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    @Builder.Default
    private ImagingOrderStatus status = ImagingOrderStatus.ORDERED;

    @Column(name = "clinical_question", length = 2000)
    private String clinicalQuestion;

    @Column(name = "contrast_required")
    private Boolean contrastRequired;

    @Column(name = "contrast_type", length = 120)
    private String contrastType;

    @Column(name = "has_contrast_allergy")
    private Boolean hasContrastAllergy;

    @Column(name = "contrast_allergy_details", length = 500)
    private String contrastAllergyDetails;

    @Column(name = "sedation_required")
    private Boolean sedationRequired;

    @Column(name = "sedation_type", length = 120)
    private String sedationType;

    @Column(name = "sedation_notes", length = 500)
    private String sedationNotes;

    @Column(name = "requires_npo")
    private Boolean requiresNpo;

    @Column(name = "has_implanted_device")
    private Boolean hasImplantedDevice;

    @Column(name = "implanted_device_details", length = 500)
    private String implantedDeviceDetails;

    @Column(name = "requires_pregnancy_test")
    private Boolean requiresPregnancyTest;

    @Column(name = "needs_interpreter")
    private Boolean needsInterpreter;

    @Column(name = "additional_protocols", length = 1000)
    private String additionalProtocols;

    @Column(name = "special_instructions", length = 1000)
    private String specialInstructions;

    @Column(name = "scheduled_date")
    private LocalDate scheduledDate;

    @Column(name = "scheduled_time", length = 50)
    private String scheduledTime;

    @Column(name = "appointment_location", length = 255)
    private String appointmentLocation;

    @Column(name = "portable_study")
    private Boolean portableStudy;

    @Column(name = "requires_authorization")
    private Boolean requiresAuthorization;

    @Column(name = "authorization_number", length = 120)
    private String authorizationNumber;

    @Column(name = "ordered_at", nullable = false)
    @Builder.Default
    private LocalDateTime orderedAt = LocalDateTime.now();

    @Column(name = "ordering_provider_name", length = 200)
    private String orderingProviderName;

    @Column(name = "ordering_provider_npi", length = 40)
    private String orderingProviderNpi;

    @Column(name = "ordering_provider_user_id")
    private UUID orderingProviderUserId;

    @Column(name = "provider_signed_at")
    private LocalDateTime providerSignedAt;

    @Column(name = "provider_signature_statement", length = 1000)
    private String providerSignatureStatement;

    @Column(name = "attestation_confirmed")
    private Boolean attestationConfirmed;

    @Column(name = "encounter_id")
    private UUID encounterId;

    @Column(name = "workflow_notes", length = 1000)
    private String workflowNotes;

    @Column(name = "requires_follow_up_call")
    private Boolean requiresFollowUpCall;

    @Column(name = "duplicate_of_recent_order")
    private Boolean duplicateOfRecentOrder;

    @Column(name = "duplicate_reference_order_id")
    private UUID duplicateReferenceOrderId;

    /**
     * Provisional rule 2 (2026-11-15, V297): the orderer chose « Commander
     * quand même » on the duplicate warning, with a reason from a fixed list.
     */
    @Column(name = "duplicate_override")
    private Boolean duplicateOverride;

    @Enumerated(EnumType.STRING)
    @Column(name = "duplicate_override_reason", length = 40)
    private com.example.hms.enums.ImagingDuplicateOverrideReason duplicateOverrideReason;

    /** Free text (required with OTHER): it can name the patient's history, so it is encrypted. */
    @Convert(converter = com.example.hms.security.EncryptedStringConverter.class)
    @Column(name = "duplicate_override_note", columnDefinition = "TEXT")
    private String duplicateOverrideNote;

    @Column(name = "duplicate_override_by_user_id")
    private UUID duplicateOverrideByUserId;

    @Column(name = "duplicate_override_by_name", length = 150)
    private String duplicateOverrideByName;

    @Column(name = "duplicate_override_at")
    private LocalDateTime duplicateOverrideAt;

    /**
     * Provisional rule 1 (2026-11-15, V297): when the study was done without
     * its required pre-authorisation number (an urgent start). Null when not
     * pending; set and cleared only by {@code ImagingPreAuthorisationPolicy}.
     */
    @Column(name = "authorization_pending_since")
    private LocalDateTime authorizationPendingSince;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "cancelled_by_user_id")
    private UUID cancelledByUserId;

    @Column(name = "cancelled_by_name", length = 150)
    private String cancelledByName;

    @Column(name = "status_updated_at")
    private LocalDateTime statusUpdatedAt;

    /**
     * When the study was completed: set once, on the order's first entry into
     * COMPLETED or RESULTS_AVAILABLE (V294). Unlike {@link #statusUpdatedAt}
     * it is not moved when a COMPLETED order is promoted at signing.
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** Records the completion time unless one is already recorded. */
    public void markCompletedAt(LocalDateTime at) {
        if (completedAt == null && at != null) {
            completedAt = at;
        }
    }

    @Column(name = "status_updated_by")
    private UUID statusUpdatedBy;

    // ── The exam record (policy rule 3, V346, provisional) ──────────────
    // Written by ImagingExamRecord and the status endpoint from the session,
    // never from a request body naming somebody.

    /** When the patient was checked in at the imaging desk; set once. */
    @Column(name = "checked_in_at")
    private LocalDateTime checkedInAt;

    @Column(name = "checked_in_by_user_id")
    private UUID checkedInByUserId;

    @Column(name = "checked_in_by_name", length = 150)
    private String checkedInByName;

    /** When the exam was started (the first move to IN_PROGRESS). */
    @Column(name = "exam_started_at")
    private LocalDateTime examStartedAt;

    /** Who performed the exam: whoever started it (or completed one nobody had started). */
    @Column(name = "performed_by_user_id")
    private UUID performedByUserId;

    /** Their staff row at the order's hospital, when they have one: the report's "performed by". */
    @Column(name = "performed_by_staff_id")
    private UUID performedByStaffId;

    @Column(name = "performed_by_name", length = 150)
    private String performedByName;

    /** The technical note (protocol used, positioning, incidents): free text, encrypted. */
    @Convert(converter = com.example.hms.security.EncryptedStringConverter.class)
    @Column(name = "exam_technical_note", columnDefinition = "TEXT")
    private String examTechnicalNote;

    @Column(name = "exam_contrast_administered")
    private Boolean examContrastAdministered;

    /** The contrast given (agent, volume, route, reaction): free text, encrypted. */
    @Convert(converter = com.example.hms.security.EncryptedStringConverter.class)
    @Column(name = "exam_contrast_details", columnDefinition = "TEXT")
    private String examContrastDetails;

    @Column(name = "exam_radiation_dose_mgy", precision = 8, scale = 3)
    private java.math.BigDecimal examRadiationDoseMgy;

    @Column(name = "exam_recorded_at")
    private LocalDateTime examRecordedAt;

    @Column(name = "exam_recorded_by_user_id")
    private UUID examRecordedByUserId;

    @Override
    public UUID getTenantOrganizationId() {
        return hospital != null && hospital.getOrganization() != null ? hospital.getOrganization().getId() : null;
    }

    @Override
    public UUID getTenantHospitalId() {
        return hospital != null ? hospital.getId() : null;
    }

    @Override
    public UUID getTenantDepartmentId() {
        return null;
    }

    @Override
    public void applyTenantScope(HospitalContext context) {
        if (context == null || ActingScopeResolver.pinnedHospitalIdOf(context) == null) {
            return;
        }
        if (this.hospital == null) {
            this.hospital = new Hospital();
            this.hospital.setId(ActingScopeResolver.pinnedHospitalIdOf(context));
        }
    }
}
