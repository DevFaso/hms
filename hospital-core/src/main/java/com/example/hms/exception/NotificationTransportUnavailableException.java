package com.example.hms.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when an outbound notification channel (SMS / OTP) is asked to
 * deliver something while it has no working transport behind it.
 *
 * <p>This is a deployment state, not a caller mistake, so it surfaces as
 * 503 rather than 400: retrying the same request unchanged is exactly the
 * right thing to do once an operator sets the missing variables.
 *
 * <p>The message is written for the person at the desk. The operator-facing
 * detail — which property is missing — belongs in the server log, never in a
 * response body: the previous {@code IllegalStateException} text ("Set
 * app.ikoddi.enabled plus api-key, organization-id and otp-app-id") reached
 * the receptionist's screen through the generic 400 handler.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class NotificationTransportUnavailableException extends RuntimeException {

    public NotificationTransportUnavailableException(String message) {
        super(message);
    }
}
