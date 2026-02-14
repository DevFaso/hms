package com.example.hms.model.medication;

import com.example.hms.enums.InteractionSeverity;
import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.EqualsAndHashCode;

/**
 * Represents a known drug-drug interaction in the system's knowledge base.
 * This can be populated from external drug interaction databases (e.g., DrugBank, FDA, clinical resources)
 * or maintained manually by clinical pharmacists.
 * 
 * Used for real-time interaction checking when prescribing medications.
 */
@Entity
@Table(
    name = "drug_interactions",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_drug_interaction_drug1", columnList = "drug1_code"),
        @Index(name = "idx_drug_interaction_drug2", columnList = "drug2_code"),
        @Index(name = "idx_drug_interaction_severity", columnList = "severity"),
        @Index(name = "idx_drug_interaction_source", columnList = "source_database")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class DrugInteraction extends BaseEntity {

    // ===== Drug Identification =====

    /**
     * First drug in the interaction pair (RxNorm code preferred).
     * Order doesn't matter - interactions are bidirectional.
     */
    @NotBlank
    @Size(max = 100)
    @Column(name = "drug1_code", nullable = false, length = 100)
    private String drug1Code;

    @NotBlank
    @Size(max = 255)
    @Column(name = "drug1_name", nullable = false, length = 255)
    private String drug1Name;

    /**
     * Second drug in the interaction pair (RxNorm code preferred).
     */
    @NotBlank
    @Size(max = 100)
    @Column(name = "drug2_code", nullable = false, length = 100)
    private String drug2Code;

    @NotBlank
    @Size(max = 255)
    @Column(name = "drug2_name", nullable = false, length = 255)
    private String drug2Name;

    // ===== Interaction Details =====

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 30)
    private InteractionSeverity severity;

    /**
     * Clinical description of the interaction mechanism and effects.
     * Example: "Concurrent use may increase the risk of serotonin syndrome..."
     */
    @Size(max = 2000)
    @Column(name = "description", length = 2000, columnDefinition = "TEXT")
    private String description;

    /**
     * Clinical recommendation for managing the interaction.
     * Example: "Monitor for signs of serotonin syndrome. Consider alternative therapy."
     */
    @Size(max = 2000)
    @Column(name = "recommendation", length = 2000, columnDefinition = "TEXT")
    private String recommendation;

    /**
     * Mechanism of interaction (pharmacokinetic, pharmacodynamic, etc.).
     * Examples: "CYP3A4 inhibition", "Additive QT prolongation", "Competitive protein binding"
     */
    @Size(max = 500)
    @Column(name = "mechanism", length = 500)
    private String mechanism;

    /**
     * Affected parameters or effects.
     * Examples: "Increased bleeding risk", "Enhanced sedation", "Hyperkalemia"
     */
    @Size(max = 500)
    @Column(name = "clinical_effects", length = 500)
    private String clinicalEffects;

    // ===== Management Guidance =====

    /**
     * Indicates if any combination of these drugs should be avoided completely.
     */
    @Column(name = "requires_avoidance")
    @Builder.Default
    private boolean requiresAvoidance = false;

    /**
     * Indicates if dose adjustment is recommended when using this combination.
     */
    @Column(name = "requires_dose_adjustment")
    @Builder.Default
    private boolean requiresDoseAdjustment = false;

    /**
     * Indicates if enhanced monitoring is required (lab tests, vitals, symptoms).
     */
    @Column(name = "requires_monitoring")
    @Builder.Default
    private boolean requiresMonitoring = false;

    /**
     * Suggested monitoring parameters.
     * Example: "INR", "Serum potassium", "Blood pressure", "CNS depression symptoms"
     */
    @Size(max = 500)
    @Column(name = "monitoring_parameters", length = 500)
    private String monitoringParameters;

    /**
     * Time interval for enhanced monitoring (hours).
     * Example: 24 = check daily, 168 = check weekly
     */
    @Column(name = "monitoring_interval_hours")
    private Integer monitoringIntervalHours;

    // ===== Source and Evidence =====

    /**
     * Source database or clinical resource.
     * Examples: "DrugBank", "FDA_Adverse_Events", "Lexicomp", "Clinical_Pharmacology", "MANUAL"
     */
    @Size(max = 100)
    @Column(name = "source_database", length = 100)
    private String sourceDatabase;

    /**
     * External reference ID from the source database.
     */
    @Size(max = 255)
    @Column(name = "external_reference_id", length = 255)
    private String externalReferenceId;

    /**
     * Evidence level for the interaction.
     * Examples: "Well-documented", "Theoretical", "Case reports", "Clinical studies"
     */
    @Size(max = 100)
    @Column(name = "evidence_level", length = 100)
    private String evidenceLevel;

    /**
     * PubMed IDs or DOIs for supporting literature (comma-separated).
     */
    @Size(max = 1000)
    @Column(name = "literature_references", length = 1000)
    private String literatureReferences;

    /**
     * Indicates if this interaction is currently active in the decision support system.
     * Can be set to false to temporarily disable outdated or disputed interactions.
     */
    @Column(name = "is_active")
    @Builder.Default
    private boolean active = true;

    @Size(max = 1000)
    @Column(name = "notes", length = 1000)
    private String notes;
}
