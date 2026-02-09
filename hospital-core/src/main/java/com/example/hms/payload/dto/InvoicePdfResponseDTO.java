package com.example.hms.payload.dto;

import java.util.Arrays;
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
        if (!(o instanceof InvoicePdfResponseDTO other)) return false;
        return Arrays.equals(content, other.content)
            && java.util.Objects.equals(invoiceNumber, other.invoiceNumber)
            && java.util.Objects.equals(invoiceId, other.invoiceId);
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(invoiceNumber, invoiceId);
        result = 31 * result + Arrays.hashCode(content);
        return result;
    }

    @Override
    public String toString() {
        return "InvoicePdfResponseDTO["
            + "content=byte[" + (content != null ? content.length : 0) + "]"
            + ", invoiceNumber=" + invoiceNumber
            + ", invoiceId=" + invoiceId
            + "]";
    }
}
