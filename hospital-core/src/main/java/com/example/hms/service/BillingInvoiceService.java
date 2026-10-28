package com.example.hms.service;

import com.example.hms.payload.dto.BillingInvoiceRequestDTO;
import com.example.hms.payload.dto.BillingInvoiceResponseDTO;
import com.example.hms.payload.dto.BillingInvoiceSearchRequest;
import com.example.hms.payload.dto.InvoicePdfResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface BillingInvoiceService {
    BillingInvoiceResponseDTO createInvoice(BillingInvoiceRequestDTO dto, Locale locale);
    BillingInvoiceResponseDTO getInvoiceById(UUID id, Locale locale);
    Page<BillingInvoiceResponseDTO> getInvoicesByPatientId(UUID patientId, Pageable pageable, Locale locale);

    /**
     * The portal patient's own invoices, at every hospital that holds any
     * (design Q1: a patient is bounded by ownership, not by a hospital). No
     * hospital scope is read. Callers MUST have bound {{@code patientId}} to the
     * principal first: PatientPortalServiceImpl resolves it from the caller
     * (or verifies a proxy grant); nothing else calls this.
     */
    Page<BillingInvoiceResponseDTO> getInvoicesForPortalPatient(UUID patientId, Pageable pageable, Locale locale);

    /**
     * QA D974: a discount, a waiver or a credit note on an issued invoice, with
     * its mandatory reason; the total is lowered and the row audited.
     */
    BillingInvoiceResponseDTO addAdjustment(UUID invoiceId, com.example.hms.payload.dto.InvoiceAdjustmentRequestDTO request,
                                            UUID staffId, Locale locale);

    /** QA D974: the discounts, waivers and credit notes of an invoice in the caller's scope. */
    List<com.example.hms.payload.dto.InvoiceAdjustmentResponseDTO> listAdjustments(UUID invoiceId);

    /** QA D982: one of the patient's issued invoices with its lines and payments; another's answers 404. */
    com.example.hms.payload.dto.portal.PortalInvoiceDetailDTO getPortalInvoiceDetail(UUID invoiceId, UUID patientId);

    /** QA D982: the patient's own invoice as a PDF. */
    InvoicePdfResponseDTO getPortalInvoicePdf(UUID invoiceId, UUID patientId, Locale locale);

    /** QA D982: the receipt of one of the patient's confirmed payments. */
    InvoicePdfResponseDTO getPortalReceiptPdf(UUID invoiceId, UUID paymentId, UUID patientId, Locale locale);
    Page<BillingInvoiceResponseDTO> getInvoicesByHospitalId(UUID hospitalId, Pageable pageable, Locale locale);
    List<BillingInvoiceResponseDTO> getOverdueInvoices(LocalDate referenceDate, Locale locale);
    BillingInvoiceResponseDTO updateInvoice(UUID id, BillingInvoiceRequestDTO dto, Locale locale);
    void deleteInvoice(UUID id, Locale locale);

    // helper used by InvoiceItemService to keep totals in sync
    void recomputeAndPersistTotals(UUID invoiceId);

    /** QA D210: every issue path moves a paid check-in copay of the same visit onto the issued invoice. */
    void creditCheckInCopays(UUID invoiceId);

    InvoicePdfResponseDTO getInvoicePdf(UUID invoiceId, Locale locale);

    Page<BillingInvoiceResponseDTO> searchInvoices(BillingInvoiceSearchRequest searchRequest, Pageable pageable, Locale locale);

    /** Record a payment against an invoice — updates amountPaid and status. */
    BillingInvoiceResponseDTO recordPayment(UUID invoiceId, UUID patientId, java.math.BigDecimal amount, Locale locale);

    /**
     * A patient paying their own invoice: applies the amount as
     * {@link #recordPayment} does AND writes the payment row with the method,
     * the provider's reference and the notes the patient entered, so a cashier
     * can reconcile it against the provider's statement.
     */
    BillingInvoiceResponseDTO recordPatientPayment(UUID invoiceId, UUID patientId, UUID recordedBy,
            com.example.hms.payload.dto.portal.PatientPaymentRequestDTO payment, Locale locale);

    /**
     * Staff shortcut: record a payment (patient resolved from the invoice) and
     * write its payment row; a missing method is recorded as OTHER.
     */
    BillingInvoiceResponseDTO recordStaffPayment(UUID invoiceId,
            com.example.hms.payload.dto.StaffPaymentRequestDTO payment, UUID recordedBy, Locale locale);

    /** Every ledger row of an invoice in the caller's hospital (QA D142). */
    List<com.example.hms.payload.dto.PaymentTransactionResponseDTO> listPayments(UUID invoiceId);

    /** A cashier confirms a patient's PENDING declaration; only then is it counted (QA D140). */
    BillingInvoiceResponseDTO confirmPayment(UUID invoiceId, UUID paymentId, UUID staffId, Locale locale);

    /** A cashier refuses a patient's PENDING declaration, with a reason (QA D140). */
    BillingInvoiceResponseDTO rejectPayment(UUID invoiceId, UUID paymentId, String reason, UUID staffId, Locale locale);

    /** Billing staff reverse a CONFIRMED payment with a reason; totals are recomputed (QA D142). */
    BillingInvoiceResponseDTO reversePayment(UUID invoiceId, UUID paymentId, String reason, UUID staffId, Locale locale);

    /** A receipt PDF for one confirmed payment (QA D141). */
    InvoicePdfResponseDTO getPaymentReceiptPdf(UUID invoiceId, UUID paymentId, Locale locale);

    /**
     * QA D149: the copay taken at check-in, as a paid invoice on the visit's
     * encounter with its cash payment in the ledger. Called inside the
     * check-in transaction.
     */
    void recordCheckInCopay(com.example.hms.model.Encounter encounter, java.math.BigDecimal amount, UUID recordedBy);

    /**
     * QA D975: the check-in copay paid by {@code method} (cash when null),
     * with its reference (required for Mobile Money, insurance and bank transfer). Returns the
     * confirmed payment row, so the desk can print its receipt; null when
     * the amount is not positive.
     */
    com.example.hms.model.PaymentTransaction recordCheckInCopay(com.example.hms.model.Encounter encounter,
        java.math.BigDecimal amount, com.example.hms.enums.PaymentMethod method, String reference, UUID recordedBy);
    /** QA D209: KPI totals over every invoice in the caller's scope. */
    com.example.hms.payload.dto.BillingSummaryDTO summarize(Locale locale);
}
