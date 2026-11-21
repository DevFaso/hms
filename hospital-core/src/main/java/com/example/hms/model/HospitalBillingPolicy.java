package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

/**
 * Policy rules 1 and 6 (provisional, V345): the billing and dispensing choices a hospital may make for itself. One
 * row per hospital that changed a default; a hospital with no row uses the defaults, which are the rules of the
 * policy report.
 */
@Entity
@Table(
    name = "hospital_billing_policies",
    schema = "hospital",
    uniqueConstraints = @UniqueConstraint(name = "uk_hbpolicy_hospital", columnNames = "hospital_id")
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class HospitalBillingPolicy extends BaseEntity {

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    /**
     * Rule 6: whether a planned, day-case, newborn, labour or transfer admission gets a consultation line by
     * default. False (the default): it is billed for its stay and the acts recorded, never for a consultation
     * nobody recorded.
     */
    @Column(name = "auto_consultation_on_planned_admission", nullable = false)
    @Builder.Default
    private boolean autoConsultationOnPlannedAdmission = false;

    /**
     * Rule 1: « prolongation exceptionnelle par le pharmacien ». Off; no behaviour is built behind it, and the
     * service refuses to turn it on until a legal text allows it.
     */
    @Column(name = "pharmacist_exceptional_renewal", nullable = false)
    @Builder.Default
    private boolean pharmacistExceptionalRenewal = false;

    @Column(name = "updated_by")
    private UUID updatedBy;
}
