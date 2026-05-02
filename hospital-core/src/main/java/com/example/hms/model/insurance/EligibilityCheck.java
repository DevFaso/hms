package com.example.hms.model.insurance;

import com.example.hms.enums.EligibilityCheckType;
import com.example.hms.enums.EligibilityScheme;
import com.example.hms.enums.EligibilityStatus;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientInsurance;
import com.example.hms.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Persistent record of one real-time eligibility or prior-auth call against a
 * West-African public-payer scheme (NHIS, CNAMGS, mutuelle, …) or a generic
 * private payer.
 *
 * <p>Append-mostly: each call writes a row. The latest row per (patient, scheme,
 * checkType) is the authoritative current state for the patient detail and
 * checkout screens. The {@code @Version} column protects against the rare race
 * where two clinicians submit a check simultaneously and the latter mutates an
 * older snapshot.
 */
@Entity
@Table(
    name = "eligibility_checks",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_elig_patient", columnList = "patient_id"),
        @Index(name = "idx_elig_hospital", columnList = "hospital_id"),
        @Index(name = "idx_elig_scheme", columnList = "scheme"),
        @Index(name = "idx_elig_patient_scheme_completed", columnList = "patient_id,scheme,completed_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"patient", "hospital", "patientInsurance", "requestedBy"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class EligibilityCheck extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_elig_patient"))
    private Patient patient;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_elig_hospital"))
    private Hospital hospital;

    /** Optional link to the {@link PatientInsurance} row this check was run against. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_insurance_id",
        foreignKey = @ForeignKey(name = "fk_elig_patient_insurance"))
    private PatientInsurance patientInsurance;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "scheme", nullable = false, length = 24)
    private EligibilityScheme scheme;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false, length = 16)
    private EligibilityCheckType checkType;

    /** The payer-side member id submitted for verification (NHIS card, CNAMGS no., …). */
    @Size(max = 64)
    @Column(name = "member_id", length = 64)
    private String memberId;

    /** Optional service / CPT-equivalent code for prior-auth requests. */
    @Size(max = 32)
    @Column(name = "service_code", length = 32)
    private String serviceCode;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private EligibilityStatus status;

    /** Raw payer response code (scheme-specific). */
    @Size(max = 32)
    @Column(name = "response_code", length = 32)
    private String responseCode;

    /** Free-text payer message (truncated). */
    @Column(name = "payer_response_text", columnDefinition = "TEXT")
    private String payerResponseText;

    /** Co-pay required at point of service, in payer currency. */
    @Column(name = "copay_amount", precision = 12, scale = 2)
    private BigDecimal copayAmount;

    @Size(max = 8)
    @Column(name = "copay_currency", length = 8)
    private String copayCurrency;

    @Column(name = "prior_auth_required")
    private Boolean priorAuthRequired;

    /** Authorisation reference returned by the payer (when {@link #checkType} = PRIOR_AUTH). */
    @Size(max = 64)
    @Column(name = "prior_auth_number", length = 64)
    private String priorAuthNumber;

    /** Date through which the eligibility / prior-auth answer is valid. */
    @Column(name = "valid_until")
    private LocalDate validUntil;

    /** Local error message if the call could not reach the payer. */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_user_id",
        foreignKey = @ForeignKey(name = "fk_elig_requested_by"))
    private User requestedBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
