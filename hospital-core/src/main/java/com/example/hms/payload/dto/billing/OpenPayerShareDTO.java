package com.example.hms.payload.dto.billing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QA D2706 (r70): one payer share on the hospital-wide « Parts assureurs » list: what a payer owes, on which
 * invoice, for which patient. The claims reviewer had no screen for it: the share was reachable only through the
 * invoice drawer, which their role cannot open.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OpenPayerShareDTO {
    private UUID id;
    private UUID invoiceId;
    private String invoiceNumber;
    private LocalDate invoiceDate;
    private String patientName;
    private String patientMrn;
    private String payerName;
    private String planName;
    private BigDecimal amount;
    private String status;
    private LocalDateTime createdAt;
}
