package com.example.hms.payload.dto;

public record EmailInvoiceRequest(
    @jakarta.validation.constraints.NotEmpty
    java.util.List<
        @jakarta.validation.constraints.NotBlank
        @jakarta.validation.constraints.Email String> to,
    java.util.List<@jakarta.validation.constraints.Email String> cc,
    java.util.List<@jakarta.validation.constraints.Email String> bcc,
    String message,
    String locale,
    boolean attachPdf
) {}
