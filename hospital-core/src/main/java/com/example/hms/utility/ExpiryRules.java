package com.example.hms.utility;

import com.example.hms.exception.FieldBusinessException;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * QA D1464: an end date chosen for an access or a consent must lie ahead. A grant born
 * expired was reported as granted, while the person it named had no access at all. Blank
 * stays allowed (open-ended). The server clock is the one the grant is later checked against.
 *
 * <p>{@code AccessExpiryGuardTest} fails when a service stores a request's expiry without it.
 */
public final class ExpiryRules {

    private ExpiryRules() {
    }

    /** The value when it is blank or still ahead; otherwise refused on {@code field}. */
    public static LocalDateTime requireFuture(String field, LocalDateTime value, String messageKey) {
        if (value != null && !value.isAfter(LocalDateTime.now(ZoneId.systemDefault()))) {
            throw new FieldBusinessException(field, messageKey);
        }
        return value;
    }
}
