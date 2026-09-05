package com.example.hms.model.pro;

import com.example.hms.model.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
 * One numbered item of a {@link ProInstrument}. Carries no text — that is
 * per-language in {@link ProInstrumentText} — and no score of its own: the
 * options do.
 */
@Entity
@Table(
    name = "pro_instrument_items",
    schema = "clinical",
    uniqueConstraints = @UniqueConstraint(name = "uq_pro_item_no", columnNames = {"instrument_id", "item_no"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"instrument", "options"})
public class ProInstrumentItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pro_item_instrument"))
    private ProInstrument instrument;

    @Column(name = "item_no", nullable = false)
    private int itemNo;

    @OneToMany(mappedBy = "item", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("optionNo ASC")
    @Builder.Default
    private List<ProInstrumentOption> options = new ArrayList<>();
}
