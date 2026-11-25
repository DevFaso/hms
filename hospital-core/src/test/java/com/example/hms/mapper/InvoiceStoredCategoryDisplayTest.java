package com.example.hms.mapper;

import com.example.hms.enums.ItemCategory;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.model.InvoiceItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceStoredCategoryDisplayTest {
    @ParameterizedTest @EnumSource(PriceListCategory.class)
    void theDtoProjectsTheStoredCategoryWithoutRepricing(PriceListCategory category) {
        InvoiceItem item = new InvoiceItem();
        item.setItemCategory(ItemCategory.SERVICE);
        item.setCoverageCategory(category);
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("1250"));
        item.setTotalPrice(new BigDecimal("2500"));
        var dto = new InvoiceItemMapper().toInvoiceItemResponseDTO(item);
        assertThat(dto.getCoverageCategory()).isEqualTo(category.name());
        assertThat(dto.getItemCategory()).isEqualTo("SERVICE");
        assertThat(dto.getUnitPrice()).isEqualByComparingTo("1250");
        assertThat(dto.getTotalPrice()).isEqualByComparingTo("2500");
        assertThat(item.getCoverageCategory()).isEqualTo(category);
    }

    @Test void legacyLinesRemainUnknownRatherThanInferringFromTheDescription() {
        InvoiceItem item = new InvoiceItem();
        item.setItemCategory(ItemCategory.SERVICE);
        item.setItemDescription("cdx Imagerie");
        assertThat(new InvoiceItemMapper().toInvoiceItemResponseDTO(item).getCoverageCategory()).isNull();
    }
}
