package com.example.hms.model;

import com.example.hms.enums.MicroCultureStatus;
import com.example.hms.enums.MicroGrowthResult;
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
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * What a validated culture report said just before one correction (QA CX-009).
 * The report itself keeps only its latest reason, and a removed isolate is a
 * deleted row, so this is the only place an organism taken off a validated report
 * can still be read. Written once, never changed.
 */
@Entity
@Table(
    name = "micro_culture_corrections",
    schema = "lab",
    indexes = {
        @Index(name = "idx_micro_correction_culture", columnList = "culture_result_id, corrected_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"cultureResult"})
public class MicroCultureCorrection extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "culture_result_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_micro_correction_culture"))
    private MicroCultureResult cultureResult;

    @Column(name = "corrected_at", nullable = false)
    private LocalDateTime correctedAt;

    @Column(name = "corrected_by_user_id")
    private UUID correctedByUserId;

    @Size(max = 500)
    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_before", nullable = false, length = 20)
    private MicroCultureStatus statusBefore;

    @Enumerated(EnumType.STRING)
    @Column(name = "growth_before", length = 20)
    private MicroGrowthResult growthBefore;

    @Size(max = 255)
    @Column(name = "gram_stain_before", length = 255)
    private String gramStainBefore;

    @Size(max = 1000)
    @Column(name = "notes_before", length = 1000)
    private String notesBefore;

    /** The organisms and their susceptibilities, one line per isolate. */
    @Column(name = "isolates_before", columnDefinition = "TEXT")
    private String isolatesBefore;
}
