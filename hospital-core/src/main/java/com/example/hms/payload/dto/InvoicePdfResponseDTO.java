package com.example.hms.payload.dto;

import java.util.UUID;

/**
 * Wrapper for invoice PDF payloads returned by the service layer.
 */
public record InvoicePdfResponseDTO(byte[] content, String invoiceNumber, UUID invoiceId) {

    public String suggestedFilename() {
        String base = (invoiceNumber != null && !invoiceNumber.isBlank()) ? invoiceNumber : invoiceId.toString();
        return sanitize(base);
    }

    private static String sanitize(String candidate) {
        return candidate.replaceAll("[^A-Za-z0-9._-]+", "_");
    }
}
