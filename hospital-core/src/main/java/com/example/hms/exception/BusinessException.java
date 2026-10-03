package com.example.hms.exception;

import com.example.hms.utility.MessageUtil;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A general business-rule refusal (400).
 *
 * <p>The first argument is either a message KEY ({@code prescription.patient.required})
 * or free text. A key is resolved in the caller's locale, with the arguments
 * that follow, through the same {@link MessageUtil} path
 * {@link ResourceNotFoundException} uses. Free text, and a key no bundle
 * carries, pass through unchanged, so the sites that throw a sentence keep
 * rendering exactly what they wrote.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class BusinessException extends RuntimeException {

    private static final Object[] NO_ARGS = new Object[0];

    private final String messageKey;
    private final transient Object[] args;

    public BusinessException(String message) {
        super(MessageUtil.resolveOrRaw(message));
        this.messageKey = message;
        this.args = NO_ARGS;
    }

    public BusinessException(String messageKey, Object... args) {
        super(MessageUtil.resolveOrRaw(messageKey, args));
        this.messageKey = messageKey;
        this.args = args != null ? args.clone() : NO_ARGS;
    }

    public BusinessException(String message, Throwable cause) {
        super(MessageUtil.resolveOrRaw(message), cause);
        this.messageKey = message;
        this.args = NO_ARGS;
    }

    /** What the site passed: the bundle key, or the free text itself. */
    public String getMessageKey() {
        return messageKey;
    }

    public Object[] getArgs() {
        return args.clone();
    }
}
