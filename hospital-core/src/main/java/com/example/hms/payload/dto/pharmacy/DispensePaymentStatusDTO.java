package com.example.hms.payload.dto.pharmacy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QA D2675 (r65): what a fill costs and how it is settled, read by the hand-over dialog. {@code due} is what the
 * patient still owes (charge less posted payments and live claims). {@code status}: NO_CHARGE (not priced, nothing
 * to collect), PAID, COVERED (a claim covers the rest), DEFERRED (« à facturer ») or UNPAID.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DispensePaymentStatusDTO {
    private UUID dispenseId;
    private BigDecimal quantity;
    private String unit;
    private BigDecimal charge;
    private BigDecimal paid;
    private BigDecimal claimed;
    private BigDecimal due;
    private String status;
    private String billingDeferredReason;
    private LocalDateTime billingDeferredAt;
    /** Policy rule 6 (V345): an inpatient item: what is due goes on the account of the open stay, nobody approves. */
    private boolean onOpenStay;
    /**
     * QA D2931 (r66): why a claim sent for this fill does not cover it for the caller, when money is still due:
     * {@code OWN_VOUCHER} (the caller alone stands behind the claim: another person confirms it or hands over),
     * {@code NO_VOUCHER} (no money desk recorded it yet), or null.
     */
    private String claimCoverGap;
}
