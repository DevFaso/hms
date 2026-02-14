package com.example.hms.model;

import com.example.hms.enums.AdmissionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admission Order Set Template - predefined order bundles for common admission scenarios
 * (e.g., CHF protocol, Pneumonia protocol, Post-Op Orthopedic Surgery, etc.)
 */
@Entity
@Table(name = "admission_order_sets")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdmissionOrderSet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Name of the order set (e.g., "CHF Admission Protocol", "Pneumonia Bundle")
     */
    @Column(nullable = false, length = 200)
    private String name;

    /**
     * Detailed description of when to use this order set
     */
    @Column(length = 1000)
    private String description;

    /**
     * Type of admission this order set is designed for
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdmissionType admissionType;

    /**
     * Target department (optional, can be used across departments if null)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    /**
     * Hospital that owns this order set (for hospital-specific protocols)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    /**
     * Order items in this set - structured as JSON array
     * Example structure:
     * [
     *   {
     *     "orderType": "LAB",
     *     "orderCode": "CBC",
     *     "orderName": "Complete Blood Count",
     *     "priority": "STAT",
     *     "frequency": "ONCE"
     *   },
     *   {
     *     "orderType": "MEDICATION",
     *     "medicationName": "Furosemide",
     *     "dose": "40mg",
     *     "route": "IV",
     *     "frequency": "BID"
     *   },
     *   {
     *     "orderType": "DIET",
     *     "dietType": "Low Sodium",
     *     "restrictions": ["No added salt"]
     *   },
     *   {
     *     "orderType": "ACTIVITY",
     *     "activityLevel": "Bed rest with bathroom privileges"
     *   },
     *   {
     *     "orderType": "MONITORING",
     *     "monitoringType": "Telemetry",
     *     "frequency": "Continuous"
     *   }
     * ]
     */
    @Type(JsonBinaryType.class)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> orderItems = new ArrayList<>();

    /**
     * Clinical guidelines/references for this order set
     */
    @Column(length = 500)
    private String clinicalGuidelines;

    /**
     * Whether this order set is currently active and available for use
     */
    @Column(nullable = false)
    private Boolean active = true;

    /**
     * Version number for tracking order set revisions
     */
    @Column(nullable = false)
    private Integer version = 1;

    /**
     * Staff member who created this order set
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_staff_id")
    private Staff createdBy;

    /**
     * Staff member who last modified this order set
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_modified_by_staff_id")
    private Staff lastModifiedBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * When this order set was deactivated (if applicable)
     */
    private LocalDateTime deactivatedAt;

    /**
     * Reason for deactivation
     */
    @Column(length = 500)
    private String deactivationReason;

    // Business methods

    /**
     * Deactivate this order set
     */
    public void deactivate(String reason, Staff deactivatedBy) {
        this.active = false;
        this.deactivatedAt = LocalDateTime.now();
        this.deactivationReason = reason;
        this.lastModifiedBy = deactivatedBy;
    }

    /**
     * Create a new version of this order set
     */
    public void incrementVersion(Staff modifiedBy) {
        this.version++;
        this.lastModifiedBy = modifiedBy;
    }

    /**
     * Get the number of orders in this set
     */
    public int getOrderCount() {
        return orderItems != null ? orderItems.size() : 0;
    }
}
