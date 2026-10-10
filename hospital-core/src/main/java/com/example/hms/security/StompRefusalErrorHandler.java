package com.example.hms.security;

import org.jspecify.annotations.Nullable;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * The STOMP ERROR frame for a refused frame. The frame's {@code message}
 * header is a stable word instead of the channel's internal wording:
 * {@link #UNAVAILABLE} when the caller could not be resolved just now
 * ({@link StompCallerUnavailableException}: a client retries with backoff),
 * {@link #ACCESS_DENIED} for an authorization refusal of a resolved caller
 * (any other {@link AccessDeniedException}: a client stops reconnecting to a
 * destination it may not subscribe to, the portal's emergency-broadcast
 * socket for a provider user). Nothing else changes: the session is still
 * closed after the ERROR.
 */
public class StompRefusalErrorHandler extends StompSubProtocolErrorHandler {

    /** The ERROR frame's {@code message} header for an authorization refusal: permanent. */
    public static final String ACCESS_DENIED = "access-denied";

    /** The ERROR frame's {@code message} header when the caller could not be resolved: retryable. */
    public static final String UNAVAILABLE = "unavailable";

    @Override
    protected Message<byte[]> handleInternal(StompHeaderAccessor errorHeaderAccessor, byte[] errorPayload,
                                             @Nullable Throwable cause,
                                             @Nullable StompHeaderAccessor clientHeaderAccessor) {
        Throwable root = cause == null ? null : NestedExceptionUtils.getMostSpecificCause(cause);
        if (root instanceof StompCallerUnavailableException) {
            errorHeaderAccessor.setMessage(UNAVAILABLE);
        } else if (root instanceof AccessDeniedException) {
            errorHeaderAccessor.setMessage(ACCESS_DENIED);
        }
        return super.handleInternal(errorHeaderAccessor, errorPayload, cause, clientHeaderAccessor);
    }
}
