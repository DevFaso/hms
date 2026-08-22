package com.example.hms.model;

import com.example.hms.enums.MicroSusceptibilityInterpretation;
import com.example.hms.enums.MicroSusceptibilityMethod;
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
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One isolate x antibiotic susceptibility row (P3 #19). The DB unique
 * constraint makes a duplicate antibiotic on the same isolate
 * unrepresentable — corrections update the row rather than append.
 */
@Entity
@Table(
    name = "micro_susceptibilities",
    schema = "lab",
    indexes = @Index(name = "idx_micro_susc_isolate", columnList = "isolate_id"),
    uniqueConstraints = @UniqueConstraint(
        name = "uq_micro_susc_isolate_antibiotic",
        columnNames = {"isolate_id", "antibiotic_name"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = "isolate")
public class MicroSusceptibility extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "isolate_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_micro_susc_isolate"))
    private MicroIsolate isolate;

    @Size(max = 150)
    @Column(name = "antibiotic_name", nullable = false, length = 150)
    private String antibioticName;

    @Size(max = 50)
    @Column(name = "antibiotic_code", length = 50)
    private String antibioticCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", length = 20)
    private MicroSusceptibilityMethod method;

    /** MIC as reported, kept textual: "&lt;=0.25", "2", "&gt;=16". */
    @Size(max = 30)
    @Column(name = "mic_value", length = 30)
    private String micValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "interpretation", nullable = false, length = 15)
    private MicroSusceptibilityInterpretation interpretation;

    @Size(max = 300)
    @Column(name = "notes", length = 300)
    private String notes;
}
