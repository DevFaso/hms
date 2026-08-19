package com.example.hms.model.platform;

import com.example.hms.enums.AcuityLevel;
import com.example.hms.enums.AdmissionType;
import com.example.hms.enums.EncounterType;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
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
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Per-hospital ADT auto-create defaults (roadmap row 24 follow-on,
 * v1.1 / Interop HL7).
 *
 * <p>Consumed by
 * {@code MllpInboundAdtVisitProjectionService} when an ADT^A01 arrives
 * with a visit-number triplet that doesn't match any existing
 * Admission. The foundation pass on row 24 (V99 +
 * {@code feat/v1.1-adt-admission-encounter-sync}) reconciled inbound
 * visits against existing rows; this configuration table is the
 * follow-on that wires up auto-create for the no-match branch.
 *
 * <p>One row per hospital, enforced by {@code uk_adt_intake_config_hospital}
 * (V103). The {@link #enabled} column gates auto-create on top of the
 * global {@code app.hl7.adt.visit-sync.auto-create.enabled} flag so a
 * tenant can opt out independently.
 *
 * <p>{@link #admittingProviderId} and {@link #departmentId} are stored
 * as raw UUIDs rather than JPA associations. Hard FKs would force a
 * config-table rewrite every time staff or department rows are
 * re-seeded; the service-layer lookup dereferences these UUIDs and
 * rejects auto-create gracefully when a referent is missing.
 */
@Entity
@Table(
    name = "adt_intake_provider_configs",
    schema = "platform",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_adt_intake_config_hospital",
            columnNames = {"hospital_id"})
    },
    indexes = {
        @Index(
            name = "idx_adt_intake_config_hospital_enabled",
            columnList = "hospital_id, enabled")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class AdtIntakeProviderConfig extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "hospital_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_adt_intake_config_hospital"))
    private Hospital hospital;

    @NotNull
    @Column(name = "admitting_provider_id", nullable = false)
    private UUID admittingProviderId;

    @Column(name = "department_id")
    private UUID departmentId;

    /**
     * Nullable. Required only when the hospital enables A04 (Encounter)
     * auto-create — Encounter's {@code assignment} field is non-null
     * with a hospital-match invariant ({@code Encounter#validate}), and
     * ADT doesn't carry an HMS assignment identifier. The service-layer
     * gate fails closed when the column is null on a hospital that
     * accepts A04 traffic. Not FK-constrained for the same reason as
     * {@link #admittingProviderId} — tolerate
     * {@code security.user_role_hospital_assignment} re-seeds.
     */
    @Column(name = "default_assignment_id")
    private UUID defaultAssignmentId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "default_admission_type", nullable = false, length = 64)
    private AdmissionType defaultAdmissionType;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "default_acuity_level", nullable = false, length = 32)
    private AcuityLevel defaultAcuityLevel;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "default_encounter_type", nullable = false, length = 64)
    private EncounterType defaultEncounterType;

    @Size(max = 500)
    @Column(name = "default_chief_complaint", length = 500, nullable = false)
    @Builder.Default
    private String defaultChiefComplaint = "Auto-created from ADT^A01";

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = false;
}
