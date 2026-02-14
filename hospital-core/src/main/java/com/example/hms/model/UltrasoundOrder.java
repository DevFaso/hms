package com.example.hms.model;

import com.example.hms.enums.UltrasoundOrderStatus;
import com.example.hms.enums.UltrasoundScanType;
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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
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
 * Entity representing an ultrasound order for prenatal imaging.
 * Tracks the entire lifecycle from order creation through scan completion and reporting.
 */
@Entity
@Table(
    name = "ultrasound_orders",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_ultrasound_patient", columnList = "patient_id"),
        @Index(name = "idx_ultrasound_hospital", columnList = "hospital_id"),
        @Index(name = "idx_ultrasound_status", columnList = "status"),
        @Index(name = "idx_ultrasound_scan_type", columnList = "scan_type"),
        @Index(name = "idx_ultrasound_ordered_date", columnList = "ordered_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
@EntityListeners(TenantEntityListener.class)
@ToString(exclude = {"patient", "hospital", "report"})
public class UltrasoundOrder extends BaseEntity implements TenantScoped {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "scan_type", nullable = false, length = 50)
    private UltrasoundScanType scanType;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private UltrasoundOrderStatus status = UltrasoundOrderStatus.ORDERED;

    @NotNull
    @Column(name = "ordered_date", nullable = false)
    @Builder.Default
    private LocalDateTime orderedDate = LocalDateTime.now();

    @Column(name = "ordered_by", length = 200)
    private String orderedBy;

    @Column(name = "gestational_age_at_order")
    private Integer gestationalAgeAtOrder; // weeks

    @Column(name = "clinical_indication", length = 2000)
    private String clinicalIndication;

    @Column(name = "scheduled_date")
    private LocalDate scheduledDate;

    @Column(name = "scheduled_time")
    private String scheduledTime;

    @Column(name = "appointment_location", length = 500)
    private String appointmentLocation;

    @Column(name = "priority", length = 20)
    @Builder.Default
    private String priority = "ROUTINE"; // ROUTINE, URGENT, STAT

    @Column(name = "is_high_risk_pregnancy")
    @Builder.Default
    private Boolean isHighRiskPregnancy = false;

    @Column(name = "high_risk_notes", length = 1000)
    private String highRiskNotes;

    @Column(name = "special_instructions", length = 1000)
    private String specialInstructions;

    @Column(name = "scan_count_for_pregnancy")
    @Builder.Default
    private Integer scanCountForPregnancy = 1;

    @OneToOne(mappedBy = "ultrasoundOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private UltrasoundReport report;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancelled_by", length = 200)
    private String cancelledBy;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

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
        return null; // Department scoping not applicable for ultrasound orders
    }

    @Override
    public void applyTenantScope(HospitalContext context) {
        if (context == null) {
            return;
        }
        // Apply hospital scope from context if not already set
        if (this.hospital == null && context.getActiveHospitalId() != null) {
            this.hospital = new Hospital();
            this.hospital.setId(context.getActiveHospitalId());
        }
    }
}
