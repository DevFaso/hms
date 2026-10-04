package com.example.hms.exception;

import com.example.hms.utility.MessageUtil;

/**
 * A domain rule refused the request (400, mapped in {@link GlobalExceptionHandler}).
 * Before the handler existed this exception fell through to the generic
 * {@code RuntimeException} handler and every rule refusal reached the client
 * as a 500.
 *
 * <p>The constructor takes the message as written: the sites that throw it
 * pass a sentence or a message they resolved themselves. {@link #ofKey}
 * resolves a bundle key in the caller's locale instead.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }

    /** A refusal whose text is the bundle entry {@code messageKey}, in the caller's locale. */
    public static BusinessRuleException ofKey(String messageKey, Object... args) {
        return new BusinessRuleException(MessageUtil.resolveOrRaw(messageKey, args));
    }
}
