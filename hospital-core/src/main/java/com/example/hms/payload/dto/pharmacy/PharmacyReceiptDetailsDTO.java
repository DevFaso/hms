package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * QA D2310: what a pharmacy receipt prints beyond the payment itself. The receipt showed a medication name,
 * a patient name, a method and a total: no quantity, no unit price, no record number, no cashier and no
 * pharmacy to go back to. Every line here is read on the server from the payment's own dispense, patient,
 * pharmacy and cashier, so the receipt never depends on what the checkout screen still holds.
 *
 * <p>The price lines ({@link #unitPrice}, {@link #lineTotal}, {@link #insurerShare}) are sent only on the
 * answer to the payment being recorded, when the price just charged is the price read: a later read of an old
 * payment would print today's list price beside yesterday's total.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PharmacyReceiptDetailsDTO {

    private final String medicationName;
    private final BigDecimal quantity;
    private final String unit;
    private final BigDecimal unitPrice;
    private final BigDecimal lineTotal;
    /** What the insurer bears of the line; absent when the patient pays it all. */
    private final BigDecimal insurerShare;

    /** The patient's record number at the hospital that took the payment. */
    private final String patientMrn;

    /** Who took the money: the cashier of record, never assumed to be the pharmacist. */
    private final String cashierName;

    private final String pharmacyName;
    private final String pharmacyAddress;
    private final String pharmacyPhone;
}
