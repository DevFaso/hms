package com.example.hms.exception;

import com.example.hms.utility.MessageUtil;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends RuntimeException {
    private final String messageKey;
    private final transient Object[] args;

    public ResourceNotFoundException(String messageKey, Object... args) {
        super(MessageUtil.resolve(messageKey, args));
        this.messageKey = messageKey;
        this.args = args;
    }

    private ResourceNotFoundException(String resolvedMessage, String messageKey, Object[] args) {
        super(resolvedMessage);
        this.messageKey = messageKey;
        this.args = args;
    }

    /**
     * For a service that is handed its caller's locale explicitly (rather than
     * relying on the request's {@code LocaleContextHolder}): the message is
     * resolved in THAT locale, once.
     */
    public static ResourceNotFoundException inLocale(java.util.Locale locale, String messageKey, Object... args) {
        return new ResourceNotFoundException(MessageUtil.resolve(locale, messageKey, args), messageKey, args);
    }

    public String getMessageKey() {
        return messageKey;
    }

    public Object[] getArgs() {
        return args;
    }
}



