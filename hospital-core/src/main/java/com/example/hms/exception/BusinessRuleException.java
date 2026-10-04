package com.example.hms.exception;

import com.example.hms.utility.MessageUtil;

/**
 * A domain rule refused the request (400, mapped in {@link GlobalExceptionHandler}).
 *
 * <p>Like {@link BusinessException}, the first argument is either a message
 * KEY, resolved in the caller's locale with the arguments that follow, or
 * free text, which passes through unchanged — so the sites that already
 * throw a sentence (or a message they resolved themselves) render exactly
 * what they wrote. Before the handler existed this exception fell through
 * to the generic {@code RuntimeException} handler and every rule refusal
 * reached the client as a 500.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(MessageUtil.resolveOrRaw(message));
    }

    public BusinessRuleException(String messageKey, Object... args) {
        super(MessageUtil.resolveOrRaw(messageKey, args));
    }
}
