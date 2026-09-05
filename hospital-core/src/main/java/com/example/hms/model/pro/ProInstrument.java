package com.example.hms.model.pro;

import com.example.hms.model.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * A standardized patient-reported-outcome instrument (Tier 2 item 47) —
 * EPDS first, PHQ-9/GAD-7 as later rows.
 *
 * <p>The instrument is data. Every {@link ProInstrumentOption} carries its
 * own score from the validated source, so nothing about an item's scoring
 * direction is coded; {@code criticalItemNo} names the one item whose
 * non-zero score escalates regardless of the total (EPDS item 10). The
 * engine ({@code ProScoring}) only sums, compares to
 * {@code positiveThreshold}, and inspects that item.
 *
 * <p>Global, not per-hospital: a validated instrument is the same everywhere.
 * Text lives in {@link ProInstrumentText} per language.
 */
@Entity
@Table(name = "pro_instruments", schema = "clinical")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"items", "texts"})
public class ProInstrument extends BaseEntity {

    @Size(max = 40)
    @Column(name = "code", nullable = false, length = 40, unique = true)
    private String code;

    @Size(max = 160)
    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Size(max = 40)
    @Column(name = "version", length = 40)
    private String version;

    /** Who authored the instrument — the attribution the V120 rule requires. */
    @Size(max = 500)
    @Column(name = "source_citation", nullable = false, length = 500)
    private String sourceCitation;

    @Size(max = 500)
    @Column(name = "licence_note", length = 500)
    private String licenceNote;

    @Column(name = "max_score", nullable = false)
    private int maxScore;

    /** A total at or above this screens positive. */
    @Column(name = "positive_threshold", nullable = false)
    private int positiveThreshold;

    /** Item whose non-zero score escalates regardless of total; null when the instrument has none. */
    @Column(name = "critical_item_no")
    private Integer criticalItemNo;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @OneToMany(mappedBy = "instrument", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("itemNo ASC")
    @Builder.Default
    private List<ProInstrumentItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "instrument", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ProInstrumentText> texts = new ArrayList<>();
}
