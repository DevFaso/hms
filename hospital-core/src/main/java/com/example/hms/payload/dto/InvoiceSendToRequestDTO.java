package com.example.hms.payload.dto;

import com.example.hms.validation.RejectsUnknownFields;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * The one address {@code POST /invoices/{invoiceId}/send-to} mails the invoice to, in the body so it
 * never reaches a URL. A field it does not take is refused rather than ignored.
 */
@RejectsUnknownFields(RejectsUnknownFields.Kind.ACTION)
public record InvoiceSendToRequestDTO(@NotBlank @Email(message = "{validation.email.invalid}") String email) {

    /** Never prints the address. */
    @Override
    public String toString() {
        return "InvoiceSendToRequestDTO[email=" + (email == null ? "absent" : "given") + "]";
    }
}
