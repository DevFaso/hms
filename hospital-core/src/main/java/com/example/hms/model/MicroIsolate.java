package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One organism grown from a culture (P3 #19). Susceptibility rows
 * ({@link MicroSusceptibility}) hang off each isolate — the panel belongs
 * to the organism, not the culture.
 */
@Entity
@Table(
    name = "micro_isolates",
    schema = "lab",
    indexes = @Index(name = "idx_micro_isolate_culture", columnList = "culture_result_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = "cultureResult")
public class MicroIsolate extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "culture_result_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_micro_isolate_culture"))
    private MicroCultureResult cultureResult;

    /** 1-based position when one culture grows several organisms. */
    @Column(name = "isolate_number", nullable = false)
    @Builder.Default
    private Integer isolateNumber = 1;

    @Size(max = 200)
    @Column(name = "organism_name", nullable = false, length = 200)
    private String organismName;

    /** Optional local/SNOMED code — deliberately free, no vocabulary is bundled. */
    @Size(max = 50)
    @Column(name = "organism_code", length = 50)
    private String organismCode;

    /** Quantitation as reported, e.g. "&gt;100,000 CFU/mL" or "moderate growth". */
    @Size(max = 50)
    @Column(name = "growth_quantity", length = 50)
    private String growthQuantity;

    @Size(max = 500)
    @Column(name = "notes", length = 500)
    private String notes;
}
