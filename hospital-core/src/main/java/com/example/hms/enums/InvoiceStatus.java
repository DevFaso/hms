package com.example.hms.enums;

public enum InvoiceStatus {
    DRAFT,
    SENT,
    PARTIALLY_PAID,
    PAID,
    CANCELLED;

    /**
     * QA D1090: the status as people read it ("Brouillon"), never the code,
     * in a refusal or a confirm. The labels are the invoice PDF's
     * ({@code pdf.invoice.statusValue.*}), so the screen and the paper agree.
     */
    public String localizedLabel() {
        return localizedLabel(null);
    }

    /** {@link #localizedLabel()} in an explicit locale (the request's when null). */
    public String localizedLabel(java.util.Locale locale) {
        String label = com.example.hms.utility.MessageUtil.resolve(locale, "pdf.invoice.statusValue." + name());
        return label == null || label.startsWith("[Missing translation]") ? name() : label;
    }

    /** The label of {@code status}, or a dash for an invoice that carries none. */
    public static String labelOf(InvoiceStatus status) {
        return status == null ? "—" : status.localizedLabel();
    }
}
