package com.example.hms.service.pharmacy;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.mapper.pharmacy.InventoryItemMapper;
import com.example.hms.model.pharmacy.InventoryItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QA D2674 (r65): packs converted to the units the stock is counted in, and the stock shown in both. */
@DisplayName("PackQuantity and the packs/units display (QA D2674)")
class PackQuantityTest {

    private final InventoryItem tablets = InventoryItem.builder().unit("comprimé").unitsPerPack(10)
            .packUnit("plaquette").quantityOnHand(new BigDecimal("4005")).build();

    @Test
    @DisplayName("a quantity in units is kept; whole packs are multiplied by the pack size")
    void converts() {
        assertThat(PackQuantity.toUnits(tablets, new BigDecimal("7"), null, "quantity")).isEqualByComparingTo("7");
        assertThat(PackQuantity.toUnits(tablets, new BigDecimal("7"), false, "quantity")).isEqualByComparingTo("7");
        assertThat(PackQuantity.toUnits(tablets, new BigDecimal("3"), true, "quantity")).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("a fraction of a pack is refused")
    void wholePacksOnly() {
        BigDecimal half = new BigDecimal("1.5");
        assertThatThrownBy(() -> PackQuantity.toUnits(tablets, half, true, "quantity"))
                .isInstanceOf(FieldBusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.pack.wholePacksOnly");
    }

    @Test
    @DisplayName("the inventory shows 4 005 tablets as 400 plaquettes + 5 tablets; an item with no pack size shows none")
    void shownInBoth() {
        var dto = new InventoryItemMapper().toResponseDTO(tablets);
        assertThat(dto.getFullPacksOnHand()).isEqualByComparingTo("400");
        assertThat(dto.getLooseUnitsOnHand()).isEqualByComparingTo("5");
        assertThat(dto.getPackUnit()).isEqualTo("plaquette");

        var plain = new InventoryItemMapper().toResponseDTO(InventoryItem.builder()
                .quantityOnHand(BigDecimal.TEN).build());
        assertThat(plain.getFullPacksOnHand()).isNull();
        assertThat(plain.getLooseUnitsOnHand()).isNull();
    }
}
