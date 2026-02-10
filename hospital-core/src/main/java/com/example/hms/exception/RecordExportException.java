package com.example.hms.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class RecordExportException extends RuntimeException {

    public RecordExportException(String message) {
        super(message);
    }

    public RecordExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
