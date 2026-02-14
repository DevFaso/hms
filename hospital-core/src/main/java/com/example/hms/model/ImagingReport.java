package com.example.hms.model;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.tenant.TenantEntityListener;
import com.example.hms.security.tenant.TenantScoped;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Cross-modality imaging report aggregate persisted in {@code clinical.imaging_reports}.
 */
@Entity
@Table(
    name = "imaging_reports",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_imaging_report_order", columnList = "imaging_order_id"),
        @Index(name = "idx_imaging_report_status", columnList = "report_status"),
        @Index(name = "idx_imaging_report_version", columnList = "imaging_order_id, is_latest_version"),
        @Index(name = "idx_imaging_report_performed_at", columnList = "performed_at"),
        @Index(name = "idx_imaging_report_interpreting_provider", columnList = "interpreting_provider_id"),
        @Index(name = "idx_imaging_report_hospital", columnList = "hospital_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@EntityListeners(TenantEntityListener.class)
@ToString(exclude = {"imagingOrder", "hospital", "organization", "department", "performedBy", "interpretingProvider", "signedBy", "criticalResultAcknowledgedBy", "measurements", "attachments", "statusHistory"})
public class ImagingReport extends BaseEntity implements TenantScoped {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "imaging_order_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_imaging_report_order"))
    private ImagingOrder imagingOrder;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_imaging_report_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id",
        foreignKey = @ForeignKey(name = "fk_imaging_report_org"))
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id",
        foreignKey = @ForeignKey(name = "fk_imaging_report_dept"))
    private Department department;

    @Column(name = "report_number", length = 80)
    private String reportNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_status", nullable = false, length = 40)
    @Builder.Default
    private ImagingReportStatus reportStatus = ImagingReportStatus.PRELIMINARY;

    @Column(name = "report_version", nullable = false)
    @Builder.Default
    private Integer reportVersion = 1;

    @Column(name = "is_latest_version", nullable = false)
    @Builder.Default
    private Boolean latestVersion = Boolean.TRUE;

    @Column(name = "study_instance_uid", length = 64)
    private String studyInstanceUid;

    @Column(name = "series_instance_uid", length = 64)
    private String seriesInstanceUid;

    @Column(name = "accession_number", length = 80)
    private String accessionNumber;

    @Column(name = "pacs_viewer_url", length = 500)
    private String pacsViewerUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "modality", length = 40)
    private ImagingModality modality;

    @Column(name = "body_region", length = 150)
    private String bodyRegion;

    @Column(name = "report_title", length = 255)
    private String reportTitle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_imaging_report_performed_by"))
    private Staff performedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interpreting_provider_id",
        foreignKey = @ForeignKey(name = "fk_imaging_report_interpreting_provider"))
    private Staff interpretingProvider;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signed_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_imaging_report_signed_by"))
    private Staff signedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "critical_result_ack_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_imaging_report_critical_ack_by"))
    private Staff criticalResultAcknowledgedBy;

    @Column(name = "performed_at")
    private LocalDateTime performedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "interpreted_at")
    private LocalDateTime interpretedAt;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "critical_result_flagged_at")
    private LocalDateTime criticalResultFlaggedAt;

    @Column(name = "critical_result_acknowledged_at")
    private LocalDateTime criticalResultAcknowledgedAt;

    @Column(name = "technique", columnDefinition = "TEXT")
    private String technique;

    @Column(name = "findings", columnDefinition = "TEXT")
    private String findings;

    @Column(name = "impression", columnDefinition = "TEXT")
    private String impression;

    @Column(name = "recommendations", columnDefinition = "TEXT")
    private String recommendations;

    @Column(name = "comparison_studies", columnDefinition = "TEXT")
    private String comparisonStudies;

    @Column(name = "contrast_administered")
    private Boolean contrastAdministered;

    @Column(name = "contrast_details", length = 1000)
    private String contrastDetails;

    @Column(name = "radiation_dose_mgy", precision = 8, scale = 3)
    private BigDecimal radiationDoseMgy;

    @Column(name = "attachments_count", nullable = false)
    @Builder.Default
    private Integer attachmentsCount = 0;

    @Column(name = "measurements_count", nullable = false)
    @Builder.Default
    private Integer measurementsCount = 0;

    @Column(name = "last_status_synced_at")
    private LocalDateTime lastStatusSyncedAt;

    @Column(name = "patient_notified_at")
    private LocalDateTime patientNotifiedAt;

    @Column(name = "is_locked_for_editing")
    private Boolean lockedForEditing;

    @Column(name = "lock_reason", length = 255)
    private String lockReason;

    @Column(name = "external_system_name", length = 150)
    private String externalSystemName;

    @Column(name = "external_report_id", length = 120)
    private String externalReportId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Builder.Default
    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequenceNumber ASC")
    private List<ImagingReportMeasurement> measurements = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<ImagingReportAttachment> attachments = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "imagingReport", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("changedAt DESC")
    private List<ImagingReportStatusHistory> statusHistory = new ArrayList<>();

    public boolean isPatientNotified() {
        return patientNotifiedAt != null;
    }

    @Override
    public UUID getTenantOrganizationId() {
        if (organization != null) {
            return organization.getId();
        }
        if (hospital != null && hospital.getOrganization() != null) {
            return hospital.getOrganization().getId();
        }
        return null;
    }

    @Override
    public UUID getTenantHospitalId() {
        return hospital != null ? hospital.getId() : null;
    }

    @Override
    public UUID getTenantDepartmentId() {
        return department != null ? department.getId() : null;
    }

    @Override
    public void applyTenantScope(HospitalContext context) {
        if (context == null || context.getActiveHospitalId() == null) {
            return;
        }
        if (hospital == null) {
            hospital = new Hospital();
            hospital.setId(context.getActiveHospitalId());
        }
        if (organization == null && context.getActiveOrganizationId() != null) {
            organization = new Organization();
            organization.setId(context.getActiveOrganizationId());
        }
        if (department == null && context.getPermittedDepartmentIds() != null && !context.getPermittedDepartmentIds().isEmpty()) {
            department = new Department();
            department.setId(context.getPermittedDepartmentIds().iterator().next());
        }
    }
}
