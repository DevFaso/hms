package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingInvoiceSearchRequest {

    private UUID patientId;
    private UUID hospitalId;
    private List<String> statuses;
    private LocalDate fromDate;
    private LocalDate toDate;
}