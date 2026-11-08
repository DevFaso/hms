package com.example.hms.validation;

import com.example.hms.enums.InvoiceAdjustmentType;
import com.example.hms.payload.dto.BillingInvoiceRequestDTO;
import com.example.hms.payload.dto.InvoiceAdjustmentRequestDTO;
import com.example.hms.payload.dto.InvoiceItemRequestDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1483 / D1484: a very large amount is refused in words on its own field. A price showed
 * the validator's raw {@code <10 digits>.<2 digits>}, and a whole-number adjustment was told it
 * had decimals, when only the service can say it is above what is left on the invoice.
 */
class InvoiceAmountBoundsTest {

    private static final String TOO_LARGE = "{billing.amount.tooLarge}";
    private static final BigDecimal HUGE = new BigDecimal("10000000000000");

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static <T> List<String> templatesOn(Set<ConstraintViolation<T>> violations, String field) {
        return violations.stream()
            .filter(v -> v.getPropertyPath().toString().equals(field))
            .map(ConstraintViolation::getMessageTemplate)
            .toList();
    }

    @Test
    void aHugeLinePriceSaysSoInWordsOnItsField() {
        InvoiceItemRequestDTO line = new InvoiceItemRequestDTO();
        line.setUnitPrice(HUGE);
        assertThat(templatesOn(validator.validate(line), "unitPrice")).containsExactly(TOO_LARGE);
    }

    @Test
    void aHugeTypedTotalSaysSoInWordsOnItsField() {
        BillingInvoiceRequestDTO invoice = BillingInvoiceRequestDTO.builder().totalAmount(HUGE).build();
        assertThat(templatesOn(validator.validate(invoice), "totalAmount")).containsExactly(TOO_LARGE);
    }

    @Test
    void aHugeWholeAdjustmentReachesTheServiceWhichSaysItIsAboveTheTotal() {
        InvoiceAdjustmentRequestDTO adjustment = new InvoiceAdjustmentRequestDTO();
        adjustment.setType(InvoiceAdjustmentType.DISCOUNT);
        adjustment.setReason("Geste commercial");
        adjustment.setAmount(HUGE);
        assertThat(templatesOn(validator.validate(adjustment), "amount")).isEmpty();
    }

    @Test
    void decimalsAreStillDecimals() {
        InvoiceAdjustmentRequestDTO adjustment = new InvoiceAdjustmentRequestDTO();
        adjustment.setAmount(new BigDecimal("10.50"));
        assertThat(templatesOn(validator.validate(adjustment), "amount")).containsExactly("{billing.payment.wholeAmountOnly}");
    }
}
