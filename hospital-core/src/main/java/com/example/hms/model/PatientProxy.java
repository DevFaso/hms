package com.example.hms.model;

import com.example.hms.enums.ProxyRelationship;
import com.example.hms.enums.ProxyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * Represents a proxy/family access grant — a Patient (grantor) allows
 * another User (proxy) to view their portal data.
 */
@Entity
@Table(name = "patient_proxies", schema = "clinical",
        indexes = {
                @Index(name = "idx_proxy_grantor", columnList = "grantor_patient_id"),
                @Index(name = "idx_proxy_grantee", columnList = "proxy_user_id"),
                @Index(name = "idx_proxy_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@lombok.Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PatientProxy extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "grantor_patient_id", nullable = false)
    private Patient grantorPatient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proxy_user_id", nullable = false)
    private User proxyUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship", nullable = false, length = 30)
    private ProxyRelationship relationship;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProxyStatus status;

    /**
     * Comma-separated permission scopes, canonical vocabulary:
     * VIEW_APPOINTMENTS, VIEW_MEDICATIONS, VIEW_LAB_RESULTS, VIEW_BILLING,
     * VIEW_RECORDS, ALL. Legacy un-prefixed tokens may persist in old rows and
     * are normalized at check time.
     */
    @Column(name = "permissions", nullable = false, length = 500)
    private String permissions;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "notes", length = 500)
    private String notes;

    /**
     * Policy 2026-11-20 (V347): when staff checked that the holder is the
     * patient's parent or legal guardian. The relationship above is what the
     * grantor declared; only a verified PARENT or LEGAL_GUARDIAN grant can
     * receive a minor's released result or a young child's sensitive record
     * ({@code ProxySensitivityPolicy}). NULL: not verified.
     */
    @Column(name = "relationship_verified_at")
    private LocalDateTime relationshipVerifiedAt;

    @Column(name = "relationship_verified_by_user_id")
    private java.util.UUID relationshipVerifiedByUserId;

    /** A parent or legal guardian whose relationship staff verified. */
    public boolean isVerifiedParentOrGuardian() {
        return relationshipVerifiedAt != null
            && (relationship == ProxyRelationship.PARENT || relationship == ProxyRelationship.LEGAL_GUARDIAN);
    }
}
