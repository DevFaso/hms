package com.example.hms.mapper.pharmacy;

import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.StockLot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D1163 (round 21): a dispensable lot carries its item's unit, so the fill form offers the prescribed form only. */
class StockLotMapperUnitTest {

    @Test
    void theLotCarriesItsItemsUnit() {
        InventoryItem item = new InventoryItem();
        item.setUnit("comprimé");
        StockLot lot = new StockLot();
        lot.setInventoryItem(item);
        lot.setLotNumber("L-1");

        assertThat(new StockLotMapper().toResponseDTO(lot).getUnit()).isEqualTo("comprimé");
        assertThat(new StockLotMapper().toResponseDTO(new StockLot()).getUnit()).isNull();
    }
}
