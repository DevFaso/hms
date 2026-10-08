package com.example.hms.model.provider;

import com.example.hms.enums.ProviderVerificationStatus;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The onboarding evidence of one external provider facility (V180, plan
 * AC-1 to AC-3).
 *
 * <p>Two layers. The <b>legal identity</b> of the business, from its official
 * registration: the RCCM extract is authoritative, and the IFU (tax id) and
 * CNSS documents must agree with it before the facility may be verified. The
 * <b>professional layer</b>: the pharmacy's operating licence or the lab's
 * ministry authorisation, and the responsible pharmacist or biologist with
 * their Ordre number. No expiry date is ever required.
 *
 * <p>These numbers identify a business, not a patient, so they are plain
 * columns. They still stay out of logs and audit descriptions (plan §6.9),
 * and so does every name here: {@link #toString()} prints the id and the
 * status only.
 *
 * <p>Platform data, not tenant data: read and written by a verified
 * super-admin only, through {@code ProviderOnboardingService}. The partial
 * unique indexes of V180 (one current verification per facility; among
 * VERIFIED rows one licence pair, one RCCM, one IFU) are deliberately not
 * declared here, because H2 would build them as FULL unique indexes.
 */
@Entity
@Table(name = "provider_verifications", schema = "hospital")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ProviderVerification extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_provider_verification_hospital"))
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProviderVerificationStatus status;

    // ── Legal identity of the business (RCCM is authoritative) ─────────
    @Column(name = "legal_name", nullable = false, length = 255)
    private String legalName;

    @Column(name = "trade_name", length = 255)
    private String tradeName;

    @Column(name = "legal_structure", nullable = false, length = 50)
    private String legalStructure;

    @Column(name = "rccm_number", nullable = false, length = 50)
    private String rccmNumber;

    @Column(name = "ifu_number", nullable = false, length = 30)
    private String ifuNumber;

    @Column(name = "cnss_number", nullable = false, length = 30)
    private String cnssNumber;

    @Column(name = "address_secteur", length = 50)
    private String addressSecteur;

    @Column(name = "address_section", length = 50)
    private String addressSection;

    @Column(name = "address_lot", length = 50)
    private String addressLot;

    @Column(name = "address_parcelle", length = 50)
    private String addressParcelle;

    @Column(name = "address_city", nullable = false, length = 100)
    private String addressCity;

    @Column(name = "address_region", nullable = false, length = 100)
    private String addressRegion;

    @Column(name = "company_phone", nullable = false, length = 30)
    private String companyPhone;

    /** The gérant (manager) of the business. */
    @Column(name = "manager_name", nullable = false, length = 200)
    private String managerName;

    @Column(name = "manager_title", nullable = false, length = 100)
    private String managerTitle;

    @Column(name = "business_started_on", nullable = false)
    private LocalDate businessStartedOn;

    @Builder.Default
    @Column(name = "ifu_matches_rccm", nullable = false)
    private boolean ifuMatchesRccm = false;

    @Builder.Default
    @Column(name = "cnss_matches_rccm", nullable = false)
    private boolean cnssMatchesRccm = false;

    // ── Professional layer ─────────────────────────────────────────────
    @Column(name = "licence_number", nullable = false, length = 100)
    private String licenceNumber;

    @Column(name = "licence_authority", nullable = false, length = 200)
    private String licenceAuthority;

    @Column(name = "licence_issued_on")
    private LocalDate licenceIssuedOn;

    /** Optional, never required (the "no expiring clinician licences" rule). */
    @Column(name = "licence_expires_on")
    private LocalDate licenceExpiresOn;

    @Column(name = "responsible_professional_name", nullable = false, length = 200)
    private String responsibleProfessionalName;

    /** The responsible pharmacist's or biologist's Ordre number. */
    @Column(name = "responsible_professional_registration", nullable = false, length = 100)
    private String responsibleProfessionalRegistration;

    // ── Decision ───────────────────────────────────────────────────────
    @Column(name = "evidence_note", columnDefinition = "TEXT")
    private String evidenceNote;

    @Column(name = "decided_by_user_id")
    private UUID decidedByUserId;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_reason", length = 1000)
    private String decisionReason;

    /** Ids and status only: no business number and no name ever reaches a log line. */
    @Override
    public String toString() {
        return "ProviderVerification{id=" + getId() + ", status=" + status + '}';
    }
}
