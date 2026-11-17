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
    private BigDecimal totalRevenue;
    private BigDecimal balance;
}
