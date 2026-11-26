package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** QA D209: the billing dashboard's KPIs over every invoice in scope. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingSummaryDTO {
    private long total;
    private long paid;
    private long outstanding;
    private long overdue;
    /**
     * QA D3981 (r77): the money kept on the invoices in scope: received less the refunds paid out (reversed payments
     * already left the amount paid). Shown as « Revenus nets ».
     */
    private BigDecimal totalRevenue;
    /** QA D3981: every franc received on those invoices, before refunds. */
    private BigDecimal grossReceived;
    /** QA D3981: what was handed back to patients on refunds paid out. */
    private BigDecimal refunded;
    private BigDecimal balance;
}
