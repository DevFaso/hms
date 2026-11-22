package com.example.hms.model;

import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3450 (r70): an exam's pregnancy flag is a decision about a NAMED exam. Whoever renames, re-categorises or
 * relinks the item (the price-list form, an import, a link by code) cannot keep a cleared « Radiographie du
 * thorax » cleared once it is called an obstetric ultrasound: the decision goes back to « not decided ».
 */
@DisplayName("D3450: renaming, re-categorising or relinking an exam puts its pregnancy decision back to undecided")
class PriceListItemPregnancyDecisionTest {

    private PriceListItem exam;

    @BeforeEach
    void aClearedChestXray() {
        exam = PriceListItem.builder().hospitalId(UUID.randomUUID()).code("RX-THORAX")
            .label("Radiographie du thorax").category(PriceListCategory.IMAGING)
            .linkType(PriceListLinkType.IMAGING_STUDY).linkKey("XRAY:radiographie du thorax")
            .mayRevealPregnancy(false).mayRevealPregnancySetBy(UUID.randomUUID())
            .mayRevealPregnancySetAt(LocalDateTime.of(2026, 11, 22, 10, 0)).build();
    }

    private void assertUndecided() {
        assertThat(exam.getMayRevealPregnancy()).isNull();
        assertThat(exam.getMayRevealPregnancySetBy()).isNull();
        assertThat(exam.getMayRevealPregnancySetAt()).isNull();
    }

    @Test
    @DisplayName("renamed: the decision is taken again")
    void renamed() {
        exam.setLabel("Échographie obstétricale");
        assertUndecided();
    }

    @Test
    @DisplayName("moved to another category: the decision is taken again")
    void recategorised() {
        exam.setCategory(PriceListCategory.OTHER_SERVICE);
        assertUndecided();
    }

    @Test
    @DisplayName("relinked (another modality or study): the decision is taken again")
    void relinked() {
        exam.setLinkKey("ULTRASOUND:echographie");
        assertUndecided();
    }

    @Test
    @DisplayName("saved again with the same name, category and link (a price change, the form re-sent): the decision stays")
    void unchanged() {
        exam.setLabel(" Radiographie du thorax ");
        exam.setCategory(PriceListCategory.IMAGING);
        exam.setLinkKey("XRAY:radiographie du thorax");
        exam.setLinkType(PriceListLinkType.IMAGING_STUDY);
        assertThat(exam.getMayRevealPregnancy()).isFalse();
        assertThat(exam.getMayRevealPregnancySetBy()).isNotNull();
    }
}
