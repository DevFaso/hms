package com.example.hms.payload.dto.billing;

import java.util.List;
import java.util.UUID;

/**
 * QA D4222 / D4223 (round 81): what the invoice e-mail dialog offers before anything is sent.
 *
 * @param patient         the patient's own address on file, or null when there is none
 * @param guarantors      the active guarantors of the patient at the invoice's hospital that have an address
 * @param maxRecipients   how many addresses one send may reach, all lists together
 * @param reasonMaxLength the longest reason kept for an address that is on no file
 * @param messageMaxLength the longest note
 */
public record InvoiceMailRecipientOptionsDTO(PatientAddress patient, List<GuarantorAddress> guarantors,
                                             int maxRecipients, int reasonMaxLength, int messageMaxLength) {

    /**
     * The patient's address and whether a send to it would go through.
     *
     * @param usable         false when a send to this address would be refused
     * @param refusalCode    why (the delivery ledger's code: OPTED_OUT, RECIPIENT_UNVERIFIED), null when usable
     * @param refusalMessage the same reason in the reader's language, null when usable
     */
    public record PatientAddress(String address, boolean usable, String refusalCode, String refusalMessage) {
    }

    /** One guarantor on file. */
    public record GuarantorAddress(UUID guarantorId, String fullName, String relationship, String address,
                                   boolean primary) {
    }
}
