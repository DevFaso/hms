package com.example.hms.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A refusal the caller is meant to read (403).
 *
 * <p>The first argument is either a message KEY, resolved in the request
 * locale with the arguments that follow when the response is written, or a
 * sentence composed for the log. Only a key reaches the client: a sentence can
 * carry a username or a hospital id, so the handler answers it with the
 * generic, localized "access denied" instead (QA D226).
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class UnauthorizedAccessException extends RuntimeException {

    private final transient Object[] args;

    public UnauthorizedAccessException(String message) {
        super(message);
        this.args = new Object[0];
    }

    public UnauthorizedAccessException(String messageKey, Object... args) {
        super(messageKey);
        this.args = args != null ? args.clone() : new Object[0];
    }

    public Object[] getArgs() {
        return args.clone();
    }
}
