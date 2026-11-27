package com.example.hms.exception;

import com.example.hms.utility.MessageUtil;
import org.springframework.http.HttpStatus;

/**
 * Nothing could be filed in the platform administrators' inbox, so nothing was
 * stored. Not a mistake in the request, and answered in the caller's language:
 * 409 when the platform has nobody to receive it (a state an administrator must
 * change), 503 when filing failed (trying again can succeed).
 */
public final class PlatformInboxUnavailableException extends RuntimeException {
    private final String messageKey;
    private final HttpStatus status;

    private PlatformInboxUnavailableException(String messageKey, HttpStatus status, Throwable cause) {
        super(MessageUtil.resolveOrRaw(messageKey), cause);
        this.messageKey = messageKey;
        this.status = status;
    }

    /** No active platform administrator can receive the item. */
    public static PlatformInboxUnavailableException noRecipient() {
        return new PlatformInboxUnavailableException("platformRequest.noRecipient", HttpStatus.CONFLICT, null);
    }

    /** The administrators could not be read, or not one item could be filed. */
    public static PlatformInboxUnavailableException notFiled(Throwable cause) {
        return new PlatformInboxUnavailableException("platformRequest.notFiled", HttpStatus.SERVICE_UNAVAILABLE, cause);
    }

    public String getMessageKey() {
        return messageKey;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
