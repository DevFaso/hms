package com.example.hms.payload.dto.billing;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Insurer claims plan PR 1 (G2, US-4): one page of the « Parts assureurs » list, with the count and the total amount
 * of every share the filters find (all pages together, whole francs), so the clerk sees what a claim would add up to
 * without loading every share of the hospital.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PayerSharePageDTO {
    private List<OpenPayerShareDTO> content;
    /** Zero-based page number. */
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private BigDecimal totalAmount;
}
