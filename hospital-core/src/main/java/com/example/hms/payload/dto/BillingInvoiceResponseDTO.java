package com.example.hms.payload.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingInvoiceResponseDTO {

    private String id;

    // Patient
    private String patientFullName;
    private String patientEmail;
    private String patientPhone;

    // Hospital
    private String hospitalName;
    private String hospitalCode;
    private String hospitalAddress;

    // Encounter
    private String encounterDescription;
    private String encounterType;
    private String encounterStatus;
    private String encounterDate;
    private String encounterTime;

    // Auditing (optional)
    private String createdByName;

    // Invoice
    private String invoiceNumber;
    private String invoiceDate;
    private String dueDate;
    private BigDecimal totalAmount;
    private BigDecimal amountPaid;
    private BigDecimal balanceDue; // Calculated: totalAmount - amountPaid

    // Insurance Information
    private List<PatientInsuranceSummaryDTO> patientInsurances;
    private BigDecimal insuranceCoverageAmount;
    private BigDecimal patientResponsibilityAmount;

    // Timestamps
    private String createdAt;
    private String updatedAt;

    private String status;
    private String notes;

    // Optional alias
    private String patientName;
}
