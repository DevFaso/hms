package com.example.hms.controller.support;

import com.example.hms.enums.AuditEventType;
import com.example.hms.exception.BusinessException;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The event-type filter of every audit search endpoint, read the same way
 * everywhere (QA D798, D801). {@code eventType} and {@code eventTypes} are
 * the same filter: either may be given, repeated or comma-separated, and a
 * row matches any of the values named. Case and surrounding spaces do not
 * matter; a blank value is no value. An unknown value is the caller's error
 * ({@code audit.eventType.invalid}, 400, naming the value), never "every
 * row".
 */
public final class AuditEventTypeParams {

    /** The unknown value is echoed in the 400; a long one is cut. */
    private static final int MAX_ECHOED_VALUE = 64;

    private AuditEventTypeParams() {
    }

    /** Every type named in either parameter; empty when none was (no filter). */
    public static Set<AuditEventType> parse(List<String> eventType, List<String> eventTypes) {
        Set<AuditEventType> types = EnumSet.noneOf(AuditEventType.class);
        addAll(types, eventType);
        addAll(types, eventTypes);
        return types;
    }

    private static void addAll(Set<AuditEventType> into, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            // Spring splits one comma-separated value into a list; a value it
            // handed over whole (an unusual converter, a direct call) is split here.
            for (String token : value.split(",")) {
                String name = token.trim();
                if (!name.isEmpty()) {
                    into.add(one(name));
                }
            }
        }
    }

    private static AuditEventType one(String name) {
        try {
            return AuditEventType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("audit.eventType.invalid",
                name.length() <= MAX_ECHOED_VALUE ? name : name.substring(0, MAX_ECHOED_VALUE) + "…");
        }
    }
}
