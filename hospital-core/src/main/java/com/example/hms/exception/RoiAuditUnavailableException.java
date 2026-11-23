package com.example.hms.exception;

import com.example.hms.utility.MessageUtil;

/** A missing durable audit is an operational failure, never a mistake in the request's fields. */
public final class RoiAuditUnavailableException extends RuntimeException {
    private final String messageKey;
    public RoiAuditUnavailableException(String messageKey) {
        super(MessageUtil.resolveOrRaw(messageKey));
        this.messageKey = messageKey;
    }
    public String getMessageKey() { return messageKey; }
}
