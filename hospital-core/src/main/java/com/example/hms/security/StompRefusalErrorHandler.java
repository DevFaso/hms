package com.example.hms.security;

import org.jspecify.annotations.Nullable;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * The STOMP ERROR frame for a refused frame. When the refusal is an
 * authorization one ({@link WebSocketSubscriptionInterceptor} throws
 * {@link AccessDeniedException}), the frame's {@code message} header is the
 * stable {@link #ACCESS_DENIED} instead of the channel's internal wording, so
 * a client stops reconnecting to a destination it may not subscribe to (the
 * portal's emergency-broadcast socket for a provider user). Nothing else
 * changes: the session is still closed after the ERROR.
 */
public class StompRefusalErrorHandler extends StompSubProtocolErrorHandler {

    /** The ERROR frame's {@code message} header for an authorization refusal. */
    public static final String ACCESS_DENIED = "access-denied";

    @Override
    protected Message<byte[]> handleInternal(StompHeaderAccessor errorHeaderAccessor, byte[] errorPayload,
                                             @Nullable Throwable cause,
                                             @Nullable StompHeaderAccessor clientHeaderAccessor) {
        if (cause != null && NestedExceptionUtils.getMostSpecificCause(cause) instanceof AccessDeniedException) {
            errorHeaderAccessor.setMessage(ACCESS_DENIED);
        }
        return super.handleInternal(errorHeaderAccessor, errorPayload, cause, clientHeaderAccessor);
    }
}
