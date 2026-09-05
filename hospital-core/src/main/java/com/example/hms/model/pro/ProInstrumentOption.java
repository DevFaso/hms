package com.example.hms.model.pro;

import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One response option of a {@link ProInstrumentItem}, with the score the
 * validated source assigns it. An item scored 0-1-2-3 and one scored
 * 3-2-1-0 are indistinguishable to the engine — the difference is data.
 */
@Entity
@Table(
    name = "pro_instrument_options",
    schema = "clinical",
    uniqueConstraints = @UniqueConstraint(name = "uq_pro_option_no", columnNames = {"item_id", "option_no"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = "item")
public class ProInstrumentOption extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pro_option_item"))
    private ProInstrumentItem item;

    @Column(name = "option_no", nullable = false)
    private int optionNo;

    @Column(name = "score", nullable = false)
    private int score;
}
