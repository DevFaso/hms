package com.example.hms.payload.dto.billing;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/** Billing-money plan PR 10 (QA D2325): what the cashier counted, per method (and mobile-money operator). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CashSessionCloseRequestDTO {

    @Valid
    @jakarta.validation.constraints.NotEmpty(message = "{billing.cashSession.countsRequired}")
    private List<CountedLine> counts;

    /** One counted method. A non-zero difference against the expected amount needs {@code explanation}. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CountedLine {
        @jakarta.validation.constraints.Pattern(regexp = com.example.hms.enums.PaymentMethod.CODES,
            flags = jakarta.validation.constraints.Pattern.Flag.CASE_INSENSITIVE,
            message = "{billing.payment.methodUnsupported}")
        private String method;
        private com.example.hms.enums.MobileMoneyOperator operator;
        private BigDecimal counted;
        @jakarta.validation.constraints.Size(max = 500)
        @com.example.hms.validation.NoCardNumber
        private String explanation;
    }
}
