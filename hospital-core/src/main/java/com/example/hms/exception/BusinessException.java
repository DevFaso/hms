package com.example.hms.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// A general business logic exception, consider making it more specific or using multiple types
@ResponseStatus(HttpStatus.BAD_REQUEST) // Or another appropriate status like CONFLICT, UNPROCESSABLE_ENTITY
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}

