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
 * resolves a bundle key in the caller's locale instead, and keeps the key and
 * its arguments, as {@link BusinessException} does (#1096 review), so a caller
 * or a test can tell which refusal it is without reading translated text.
 */
public class BusinessRuleException extends RuntimeException {

    private final String messageKey;
    private final transient Object[] args;

    public BusinessRuleException(String message) {
        super(message);
        this.messageKey = null;
        this.args = new Object[0];
    }

    private BusinessRuleException(String messageKey, Object[] args, String message) {
        super(message);
        this.messageKey = messageKey;
        this.args = args == null ? new Object[0] : args.clone();
    }

    /** A refusal whose text is the bundle entry {@code messageKey}, in the caller's locale. */
    public static BusinessRuleException ofKey(String messageKey, Object... args) {
        return new BusinessRuleException(messageKey, args, MessageUtil.resolveOrRaw(messageKey, args));
    }

    /** The bundle key of a refusal made with {@link #ofKey}; null for one built from a message. */
    public String getMessageKey() {
        return messageKey;
    }

    /** The arguments of the key, in order. */
    public Object[] getArgs() {
        return args.clone();
    }
}
