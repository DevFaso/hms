package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
    private String patientId;
    private String patientFullName;
    private String patientEmail;
    private String patientPhone;

    // Hospital
    private String hospitalName;
    private String hospitalCode;
    private String hospitalAddress;

    // Encounter
    /** The encounter's code, which the edit form sends back as encounterReference (QA D148). */
    private String encounterCode;
    private String encounterType;
    private String encounterStatus;
    private String encounterDate;
    private String encounterTime;

    // Auditing (optional)
    private String createdByName;

    /** QA D428: the visit invoice a folded check-in copay was credited to. */
    private String creditedToInvoiceNumber;

    /** QA D936: a copay taken at check-in, credited to the visit invoice once one is issued. */
    private boolean checkInCopay;

    /**
     * QA D421: what the patient has declared and staff have not confirmed
     * yet. Set on the patient portal's reads; it counts against the balance.
     */
    private BigDecimal pendingDeclaredAmount;

    // Invoice
    private String invoiceNumber;
    private String invoiceDate;
    private String dueDate;
    private BigDecimal totalAmount;
    private BigDecimal amountPaid;
    private BigDecimal balanceDue; // InvoiceBalance.dueOf: the patient's share less what the hospital holds
    /** QA D1466: the amount paid above the total, owed back to the patient (a copay above the visit total). */
    private BigDecimal refundDue;
    /** Billing-money plan PR 8 (QA D1900): what refunds already handed back to the patient. */
    private BigDecimal amountRefunded;

    // Insurance Information
    private List<PatientInsuranceSummaryDTO> patientInsurances;
    /** Billing-money plan PR 7: what the payers owe on the invoice ("Part assureur"); 0 when no payer covers it. */
    private BigDecimal insuranceCoverageAmount;
    /** Billing-money plan PR 7: the patient's share of the bill before payments ("Part patient"). */
    private BigDecimal patientResponsibilityAmount;
    /**
     * Billing-money plan PR 7: the payers' shares, oldest first, with their status and, for a rejected one, the
     * payer's reason. Filled on the invoice detail (staff and patient); null on lists.
     */
    private List<com.example.hms.payload.dto.billing.InvoicePayerShareDTO> payerShares;

    // Timestamps
    private String createdAt;
    private String updatedAt;

    private String status;
    private String notes;

    /** QA D1069: why, and when (ISO, server time), a cancelled invoice was cancelled. */
    private String cancelReason;
    private String cancelledAt;

    // Optional alias
    private String patientName;
}
