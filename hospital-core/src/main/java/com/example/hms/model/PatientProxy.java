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

    /** Comma-separated permission scopes: APPOINTMENTS,LAB_RESULTS,MEDICATIONS,VITALS,BILLING,ALL */
    @Column(name = "permissions", nullable = false, length = 500)
    private String permissions;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "notes", length = 500)
    private String notes;
}
