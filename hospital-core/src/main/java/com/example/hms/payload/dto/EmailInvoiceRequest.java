package com.example.hms.payload.dto;

public record EmailInvoiceRequest(
    @jakarta.validation.constraints.NotEmpty
    java.util.List<
        @jakarta.validation.constraints.NotBlank
        @jakarta.validation.constraints.Email(message = "{validation.email.invalid}") String> to,
    java.util.List<@jakarta.validation.constraints.Email(message = "{validation.email.invalid}") String> cc,
    java.util.List<@jakarta.validation.constraints.Email(message = "{validation.email.invalid}") String> bcc,
    String message,
    String locale,
    Boolean attachPdf
) {
    // A body that leaves this out or sends null gets the documented default
    // (QA D23): Jackson 3 refuses a null for a primitive record component.
    public EmailInvoiceRequest {
        if (attachPdf == null) {
            attachPdf = Boolean.FALSE;
        }
    }
}
