package com.example.hms.enums;

public enum RoutingDecisionStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    COMPLETED,
    CANCELLED;

    /** QA D1090 class: the status as people read it, never the code, in a refusal. */
    public String localizedLabel() {
        return com.example.hms.utility.MessageUtil.resolveLabel(null, "stock.routing.statusLabel." + name(), name());
    }

    /** The label of {@code status}, or a dash for a decision that carries none. */
    public static String labelOf(RoutingDecisionStatus status) {
        return status == null ? "—" : status.localizedLabel();
    }
}
