package com.example.hms.upload;

import com.example.hms.exception.BusinessException;

/**
 * An uploaded file was refused for what it is: its size, its type, or content
 * that does not match its name (400, in the caller's language).
 */
public class UploadRefusedException extends BusinessException {

    public UploadRefusedException(String messageKey, Object... args) {
        super(messageKey, args);
    }
}
