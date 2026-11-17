package com.example.hms.enums;

public enum RoutingDecisionStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    COMPLETED,
    CANCELLED;

    /** QA D1090 class: the status as people read it, never the code, in a refusal. */
    public String localizedLabel() {
        String label = com.example.hms.utility.MessageUtil.resolve((java.util.Locale) null,
            "stock.routing.statusLabel." + name());
        return label == null || label.startsWith("[Missing translation]") ? name() : label;
    }

    /** The label of {@code status}, or a dash for a decision that carries none. */
    public static String labelOf(RoutingDecisionStatus status) {
        return status == null ? "—" : status.localizedLabel();
    }
}
