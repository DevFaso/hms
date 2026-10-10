package com.example.hms.security;

import org.jspecify.annotations.Nullable;
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
        String verdict = classify(cause);
        if (verdict != null) {
            errorHeaderAccessor.setMessage(verdict);
        }
        return super.handleInternal(errorHeaderAccessor, errorPayload, cause, clientHeaderAccessor);
    }

    /**
     * The first refusal in the cause chain decides, however it was wrapped
     * (the channel's MessageDeliveryException, or anything that wraps the
     * interceptor's exception in turn): {@link #UNAVAILABLE} or
     * {@link #ACCESS_DENIED}; {@code null} when the chain holds no refusal.
     */
    static String classify(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof StompCallerUnavailableException) {
                return UNAVAILABLE;
            }
            if (t instanceof AccessDeniedException) {
                return ACCESS_DENIED;
            }
        }
        return null;
    }
}
