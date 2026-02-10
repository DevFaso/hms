package com.example.hms.model.signature;

import com.example.hms.enums.SignatureStatus;
import com.example.hms.enums.SignatureType;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Generic digital signature tracking entity for all types of clinical reports.
 * Story #17: Generic Report Signing API
 * 
 * This entity provides a centralized audit trail for electronic signatures
 * across discharge summaries, lab results, imaging reports, operative notes,
 * and other clinical documentation.
 */
@Entity
@Table(
    name = "digital_signatures",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_digital_signature_report", columnList = "report_type, report_id"),
        @Index(name = "idx_digital_signature_signed_by", columnList = "signed_by_staff_id"),
        @Index(name = "idx_digital_signature_status", columnList = "status"),
        @Index(name = "idx_digital_signature_hospital", columnList = "hospital_id"),
        @Index(name = "idx_digital_signature_datetime", columnList = "signature_date_time"),
        @Index(name = "idx_digital_signature_hash", columnList = "signature_hash")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_active_signature_per_report", 
            columnNames = {"report_type", "report_id", "signed_by_staff_id", "status"})
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"signedBy", "hospital"})
public class DigitalSignature extends BaseEntity {

    /**
     * Type of report being signed (e.g., DISCHARGE_SUMMARY, LAB_RESULT)
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 50)
    private SignatureType reportType;

    /**
     * ID of the specific report being signed
     */
    @NotNull
    @Column(name = "report_id", nullable = false)
    private UUID reportId;

    /**
     * Staff member who signed the report
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "signed_by_staff_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_digital_signature_staff"))
    private Staff signedBy;

    /**
     * Hospital context where signature was performed
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_digital_signature_hospital"))
    private Hospital hospital;

    /**
     * Electronic signature value (e.g., typed name, biometric data reference)
     */
    @NotBlank
    @Size(max = 2000)
    @Column(name = "signature_value", nullable = false, length = 2000)
    private String signatureValue;

    /**
     * Timestamp when signature was created
     */
    @NotNull
    @Column(name = "signature_date_time", nullable = false)
    private LocalDateTime signatureDateTime;

    /**
     * Current status of the signature (PENDING, SIGNED, REVOKED, EXPIRED, INVALID)
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private SignatureStatus status = SignatureStatus.PENDING;

    /**
     * SHA-256 hash of signature value for verification
     */
    @NotBlank
    @Size(max = 64)
    @Column(name = "signature_hash", nullable = false, length = 64)
    private String signatureHash;

    /**
     * IP address from which signature was created
     */
    @Size(max = 45)
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /**
     * Device/user agent information
     */
    @Size(max = 500)
    @Column(name = "device_info", length = 500)
    private String deviceInfo;

    /**
     * Optional notes provided with the signature
     */
    @Size(max = 2000)
    @Column(name = "signature_notes", length = 2000)
    private String signatureNotes;

    /**
     * Reason for revocation (if status is REVOKED)
     */
    @Size(max = 1000)
    @Column(name = "revocation_reason", length = 1000)
    private String revocationReason;

    /**
     * Timestamp when signature was revoked
     */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /**
     * User ID who revoked the signature
     */
    @Column(name = "revoked_by_user_id")
    private UUID revokedByUserId;

    /**
     * Display name of user who revoked the signature
     */
    @Size(max = 255)
    @Column(name = "revoked_by_display", length = 255)
    private String revokedByDisplay;

    /**
     * Expiration timestamp for the signature (optional)
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /**
     * Verification count - how many times signature has been verified
     */
    @Column(name = "verification_count", nullable = false)
    @Builder.Default
    private Integer verificationCount = 0;

    /**
     * Last verification timestamp
     */
    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    /**
     * Audit trail of all actions performed on this signature
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "digital_signature_audit_log",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "signature_id"),
        foreignKey = @ForeignKey(name = "fk_signature_audit_log_signature")
    )
    @OrderColumn(name = "log_order")
    @Builder.Default
    private List<SignatureAuditEntry> auditLog = new ArrayList<>();

    /**
     * Additional metadata as JSON (optional)
     */
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    @Version
    private Long version;

    // Helper methods

    /**
     * Mark signature as signed (finalized)
     */
    public void markAsSigned() {
        this.status = SignatureStatus.SIGNED;
        addAuditEntry("SIGNED", signedBy.getId(), signedBy.getFullName(), 
            "Signature successfully applied", null, null);
    }

    /**
     * Revoke the signature
     */
    public void revoke(UUID revokedBy, String revokerName, String reason, String ipAddress, String deviceInfo) {
        this.status = SignatureStatus.REVOKED;
        this.revokedAt = LocalDateTime.now();
        this.revokedByUserId = revokedBy;
        this.revokedByDisplay = revokerName;
        this.revocationReason = reason;
        addAuditEntry("REVOKED", revokedBy, revokerName, reason, ipAddress, deviceInfo);
    }

    /**
     * Mark signature as expired
     */
    public void markAsExpired() {
        this.status = SignatureStatus.EXPIRED;
        addAuditEntry("EXPIRED", signedBy.getId(), signedBy.getFullName(), 
            "Signature expired based on expiration date", null, null);
    }

    /**
     * Record verification attempt
     */
    public void recordVerification(boolean success, UUID verifiedBy, String verifierName, 
                                  String ipAddress, String deviceInfo) {
        this.verificationCount++;
        this.lastVerifiedAt = LocalDateTime.now();
        String action = success ? "VERIFIED_SUCCESS" : "VERIFIED_FAILURE";
        String details = success ? "Signature verified successfully" : "Signature verification failed";
        addAuditEntry(action, verifiedBy, verifierName, details, ipAddress, deviceInfo);
    }

    /**
     * Add entry to audit log
     */
    public void addAuditEntry(String action, UUID performedBy, String performedByDisplay,
                             String details, String ipAddress, String deviceInfo) {
        if (auditLog == null) {
            auditLog = new ArrayList<>();
        }
        SignatureAuditEntry entry = SignatureAuditEntry.builder()
            .action(action)
            .performedByUserId(performedBy)
            .performedByDisplay(performedByDisplay)
            .performedAt(LocalDateTime.now())
            .details(details)
            .ipAddress(ipAddress)
            .deviceInfo(deviceInfo)
            .build();
        auditLog.add(entry);
    }

    /**
     * Check if signature is currently valid
     */
    public boolean isValid() {
        return status == SignatureStatus.SIGNED
            && (expiresAt == null || !LocalDateTime.now().isAfter(expiresAt));
    }

    /**
     * Check if signature can be revoked
     */
    public boolean canRevoke() {
        return status == SignatureStatus.SIGNED;
    }

    @PrePersist
    private void prePersist() {
        if (signatureDateTime == null) {
            signatureDateTime = LocalDateTime.now();
        }
        // Check expiration on persist
        if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) {
            markAsExpired();
        }
    }

    @PreUpdate
    private void preUpdate() {
        // Check expiration on update
        if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt) 
            && status == SignatureStatus.SIGNED) {
            markAsExpired();
        }
    }
}
