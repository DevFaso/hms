package com.example.hms.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ERROR frame for a refused SUBSCRIBE says "access-denied" (the client
 * stops reconnecting); any other failure keeps its own message.
 */
class StompRefusalErrorHandlerTest {

    private final StompRefusalErrorHandler handler = new StompRefusalErrorHandler();

    private static Message<byte[]> subscribe() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/emergency-broadcast");
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static String message(Message<byte[]> error) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(error);
        assertThat(accessor.getCommand()).isEqualTo(StompCommand.ERROR);
        return accessor.getMessage();
    }

    @Test
    @DisplayName("an authorization refusal, wrapped as the channel wraps it, reads access-denied")
    void accessDeniedIsRecognisable() {
        MessageDeliveryException wrapped = new MessageDeliveryException(subscribe(),
            "Failed to send message to ExecutorSubscribableChannel[clientInboundChannel]",
            new AccessDeniedException("Subscription to this destination is not permitted"));

        assertThat(message(handler.handleClientMessageProcessingError(subscribe(), wrapped)))
            .isEqualTo(StompRefusalErrorHandler.ACCESS_DENIED);
    }

    @Test
    @DisplayName("a caller that could not be resolved reads unavailable (retryable), not access-denied")
    void unresolvedCallerIsUnavailable() {
        MessageDeliveryException wrapped = new MessageDeliveryException(subscribe(),
            "Failed to send message to ExecutorSubscribableChannel[clientInboundChannel]",
            new StompCallerUnavailableException("This destination cannot be authorized right now"));

        assertThat(message(handler.handleClientMessageProcessingError(subscribe(), wrapped)))
            .isEqualTo(StompRefusalErrorHandler.UNAVAILABLE);
    }

    @Test
    @DisplayName("an AccessDeniedException that itself wraps a cause is still read as access-denied (the chain decides, not its root)")
    void wrappedAccessDeniedIsRecognisable() {
        MessageDeliveryException wrapped = new MessageDeliveryException(subscribe(), "delivery failed",
            new IllegalStateException("interceptor failed",
                new AccessDeniedException("not permitted", new IllegalArgumentException("inner detail"))));

        assertThat(message(handler.handleClientMessageProcessingError(subscribe(), wrapped)))
            .isEqualTo(StompRefusalErrorHandler.ACCESS_DENIED);
    }

    @Test
    @DisplayName("any other failure keeps its own message")
    void otherFailuresKeepTheirMessage() {
        MessageDeliveryException other = new MessageDeliveryException(subscribe(), "broker unavailable",
            new IllegalStateException("down"));

        assertThat(message(handler.handleClientMessageProcessingError(subscribe(), other)))
            .isEqualTo("broker unavailable");
    }
}
