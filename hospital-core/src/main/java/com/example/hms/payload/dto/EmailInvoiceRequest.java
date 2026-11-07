package com.example.hms.payload.dto;

public record EmailInvoiceRequest(
    @jakarta.validation.constraints.NotEmpty
    @jakarta.validation.constraints.Size(max = EmailInvoiceRequest.MAX_RECIPIENTS)
    java.util.List<
        @jakarta.validation.constraints.NotBlank
        @jakarta.validation.constraints.Email(message = "{validation.email.invalid}") String> to,
    @jakarta.validation.constraints.Size(max = EmailInvoiceRequest.MAX_RECIPIENTS)
    java.util.List<@jakarta.validation.constraints.Email(message = "{validation.email.invalid}") String> cc,
    @jakarta.validation.constraints.Size(max = EmailInvoiceRequest.MAX_RECIPIENTS)
    java.util.List<@jakarta.validation.constraints.Email(message = "{validation.email.invalid}") String> bcc,
    @jakarta.validation.constraints.Size(max = EmailInvoiceRequest.MAX_MESSAGE_LENGTH)
    String message,
    @jakarta.validation.constraints.Size(max = 35)
    String locale,
    Boolean attachPdf
) {
    /**
     * QA round 34: one invoice goes to a patient and those who pay for them, with a short
     * note; a 20 000-character message to 60 recipients was accepted and mailed. These are
     * this form's limits, not a mail server's: 10 on each list, 2000 characters.
     */
    public static final int MAX_RECIPIENTS = 10;
    public static final int MAX_MESSAGE_LENGTH = 2000;

    // A body that leaves this out or sends null gets the documented default
    // (QA D23): Jackson 3 refuses a null for a primitive record component.
    public EmailInvoiceRequest {
        if (attachPdf == null) {
            attachPdf = Boolean.FALSE;
        }
    }
}
