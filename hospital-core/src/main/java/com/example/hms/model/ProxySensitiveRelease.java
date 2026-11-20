package com.example.hms.model;

import com.example.hms.enums.SensitivityCategory;
import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Policy 2026-11-20 (V347, provisional): a clinician released one sensitive
 * result of a patient under 18 to one verified parent or guardian.
 *
 * <p>Burkina's Loi 030-2008 gives the parents of a tested minor a right to
 * the HIV result (art. 18) and makes informing them lawful (art. 17), so the
 * result is never blocked for good; it is hidden from every proxy until a
 * doctor or a midwife who has care of the patient releases it, with a reason.
 * A release names one lab order and one grant: a later result of the same
 * test is hidden again until it is released in turn.
 *
 * <p>Ids only, by design: the proxy read asks "is this order released to this
 * grant?" and must never load a clinical row to answer.
 */
@Entity
@Table(name = "proxy_sensitive_releases", schema = "clinical",
    uniqueConstraints = @UniqueConstraint(name = "uq_proxy_release_grant_order",
        columnNames = {"proxy_grant_id", "lab_order_id"}),
    indexes = @Index(name = "idx_proxy_release_patient", columnList = "patient_id, hospital_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ProxySensitiveRelease extends BaseEntity {

    /** The hospital whose clinician released the result (the one that holds the order). */
    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    /** The family-access grant the result is released to. */
    @Column(name = "proxy_grant_id", nullable = false, updatable = false)
    private UUID proxyGrantId;

    @Column(name = "proxy_user_id", nullable = false, updatable = false)
    private UUID proxyUserId;

    @Column(name = "lab_order_id", nullable = false, updatable = false)
    private UUID labOrderId;

    /** The category the result carried when it was released. */
    @Enumerated(EnumType.STRING)
    @Column(name = "sensitivity_category", nullable = false, length = 32, updatable = false)
    private SensitivityCategory sensitivityCategory;

    /** Why the clinician released it. Encrypted: it can say what the result is. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "reason", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String reason;

    @Column(name = "released_by_user_id", nullable = false, updatable = false)
    private UUID releasedByUserId;

    @Column(name = "released_at", nullable = false, updatable = false)
    private LocalDateTime releasedAt;
}
