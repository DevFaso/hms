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
 * One piece of instrument text in one language. {@code itemNo} 0 with
 * {@code optionNo} 0 is the instrument's own instruction; {@code optionNo}
 * 0 on an item is the item prompt; other rows are option labels. Zero
 * rather than null so the unique constraint actually holds.
 *
 * <p>Loaded from the validated source through the import endpoint — never
 * authored in code.
 */
@Entity
@Table(
    name = "pro_instrument_texts",
    schema = "clinical",
    uniqueConstraints = @UniqueConstraint(name = "uq_pro_text",
        columnNames = {"instrument_id", "language", "item_no", "option_no"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = "instrument")
public class ProInstrumentText extends BaseEntity {

    public static final int INSTRUMENT_LEVEL = 0;
    public static final int PROMPT = 0;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pro_text_instrument"))
    private ProInstrument instrument;

    @Column(name = "language", nullable = false, length = 8)
    private String language;

    @Column(name = "item_no", nullable = false)
    @Builder.Default
    private int itemNo = INSTRUMENT_LEVEL;

    @Column(name = "option_no", nullable = false)
    @Builder.Default
    private int optionNo = PROMPT;

    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;
}
