package com.example.hms.payload.dto.portal;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Billing-money plan PR 8 (QA D1900): a refund handed back to the patient, as the patient sees it ("Remboursé :
 * 500 F CFA le ..."): the amount, how and when. The staff's reasons and names stay with billing.
 */
public record PortalRefundDTO(UUID id, BigDecimal amount, String method, String mobileMoneyOperator,
                              LocalDateTime paidAt) {
}
