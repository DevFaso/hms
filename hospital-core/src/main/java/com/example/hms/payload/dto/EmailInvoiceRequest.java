package com.example.hms.payload.dto;

public record EmailInvoiceRequest(
    @jakarta.validation.constraints.NotEmpty
    @jakarta.validation.constraints.Size(max = EmailInvoiceRequest.MAX_RECIPIENTS,
        message = EmailInvoiceRequest.TOO_MANY)
    java.util.List<
        @jakarta.validation.constraints.NotBlank
        @jakarta.validation.constraints.Email(message = "{validation.email.invalid}") String> to,
    @jakarta.validation.constraints.Size(max = EmailInvoiceRequest.MAX_RECIPIENTS,
        message = EmailInvoiceRequest.TOO_MANY)
    java.util.List<@jakarta.validation.constraints.Email(message = "{validation.email.invalid}") String> cc,
    @jakarta.validation.constraints.Size(max = EmailInvoiceRequest.MAX_RECIPIENTS,
        message = EmailInvoiceRequest.TOO_MANY)
    java.util.List<@jakarta.validation.constraints.Email(message = "{validation.email.invalid}") String> bcc,
    @jakarta.validation.constraints.Size(max = EmailInvoiceRequest.MAX_MESSAGE_LENGTH)
    String message,
    /**
     * Ignored since QA D2200: the mail and its PDF are written in the patient's stated
     * language (French when none), never the clerk's. Kept so existing clients still validate.
     */
    @jakarta.validation.constraints.Size(max = 35)
    String locale,
    Boolean attachPdf,
    /**
     * Why the invoice also goes to an address that is neither the patient's own nor a guarantor's on file.
     * Required for such an address, kept in the audit trail; ignored otherwise.
     */
    @jakarta.validation.constraints.Size(max = EmailInvoiceRequest.MAX_REASON_LENGTH)
    String reason
) {
    /**
     * One invoice goes to the patient and those who pay for them: at most this many addresses in one send, all
     * lists together (the service counts them; each list is capped at the same number). 2000 characters for the
     * note.
     */
    public static final int MAX_RECIPIENTS = 3;
    /**
     * QA D4230 (round 81): one list over the cap is refused in the same words as the three lists together
     * ({@code invoiceMail.recipient.tooMany}); it used to answer « la taille doit être comprise entre 0 et 3 ».
     */
    public static final String TOO_MANY = "{validation.invoiceMail.tooMany}";
    public static final int MAX_MESSAGE_LENGTH = 2000;
    public static final int MAX_REASON_LENGTH = 500;

    /** A send to addresses on file only: no reason. */
    public EmailInvoiceRequest(java.util.List<String> to, java.util.List<String> cc, java.util.List<String> bcc,
                               String message, String locale, Boolean attachPdf) {
        this(to, cc, bcc, message, locale, attachPdf, null);
    }

    // A body that leaves this out or sends null gets the documented default
    // (QA D23): Jackson 3 refuses a null for a primitive record component.
    public EmailInvoiceRequest {
        if (attachPdf == null) {
            attachPdf = Boolean.FALSE;
        }
    }
}
