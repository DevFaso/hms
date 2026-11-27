package com.example.hms.validation;

import com.example.hms.payload.dto.pharmacy.InventoryItemRequestDTO;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PharmacyPriceMessageTest {
    @Test
    void fractionalPricesKeepTheWholeFrancConstraintAndUseAnApplicationMessage() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var violations = validator.validateValue(InventoryItemRequestDTO.class, "unitPrice", new BigDecimal("55.5"));
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessageTemplate()).isEqualTo("{pharmacy.inventory.price.whole}");
            for (String value : new String[]{"0", "55", "9999999999"}) {
                assertThat(validator.validateValue(InventoryItemRequestDTO.class, "unitPrice", new BigDecimal(value)))
                    .as(value).isEmpty();
            }
            assertThat(validator.validateValue(InventoryItemRequestDTO.class, "unitPrice", new BigDecimal("10000000000")))
                .hasSize(1);
        }
    }
}
