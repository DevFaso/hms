package com.example.hms.security;

import org.springframework.security.access.AccessDeniedException;

/**
 * A STOMP frame refused because the caller could not be resolved just now (a
 * database blip, a resolution failure), not because the caller may not have
 * it. Still a refusal (fail closed), so it is an {@link AccessDeniedException};
 * {@link StompRefusalErrorHandler} reports it as
 * {@link StompRefusalErrorHandler#UNAVAILABLE}, which a client retries with
 * backoff, never as the permanent
 * {@link StompRefusalErrorHandler#ACCESS_DENIED}.
 */
public class StompCallerUnavailableException extends AccessDeniedException {

    public StompCallerUnavailableException(String message) {
        super(message);
    }
}
