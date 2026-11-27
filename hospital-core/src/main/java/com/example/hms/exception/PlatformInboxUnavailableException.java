package com.example.hms.exception;

import com.example.hms.utility.MessageUtil;

/**
 * Nothing could be filed in the platform administrators' inbox (nobody to
 * receive it, or every filing failed). An operational state, not a mistake in
 * the request: answered 503, in the caller's language, and nothing was stored.
 */
public final class PlatformInboxUnavailableException extends RuntimeException {
    private final String messageKey;

    public PlatformInboxUnavailableException(String messageKey) {
        super(MessageUtil.resolveOrRaw(messageKey));
        this.messageKey = messageKey;
    }

    public PlatformInboxUnavailableException(String messageKey, Throwable cause) {
        super(MessageUtil.resolveOrRaw(messageKey), cause);
        this.messageKey = messageKey;
    }

    public String getMessageKey() {
        return messageKey;
    }
}
