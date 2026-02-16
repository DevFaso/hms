package com.example.hms.payload.dto;

import java.util.Arrays;
import java.util.Objects;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InvoicePdfResponseDTO(byte[] otherContent, String otherNumber, UUID otherId))) return false;
        return Arrays.equals(content, otherContent)
            && Objects.equals(invoiceNumber, otherNumber)
            && Objects.equals(invoiceId, otherId);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(invoiceNumber, invoiceId) + Arrays.hashCode(content);
    }

    @Override
    public String toString() {
        return "InvoicePdfResponseDTO[content=byte[" + (content != null ? content.length : 0)
            + "], invoiceNumber=" + invoiceNumber + ", invoiceId=" + invoiceId + "]";
    }
}
