package com.example.hms.service.integration;

import java.util.List;

/**
 * The message keys an {@link IkoddiGateway#sendSms} failure carries, shared by both providers so a
 * caller can tell what happened without reading a transport message (which can quote the number).
 *
 * <ul>
 *   <li>{@link #REJECTED}: the provider answered 4xx: it refused the message. Sending the same
 *       request again fails the same way.</li>
 *   <li>{@link #AUTH_FAILED}: the provider refused the platform's credentials (401/403). Nothing
 *       was accepted.</li>
 *   <li>{@link #UNREACHABLE}: no token could be obtained (Orange). Nothing was sent.</li>
 *   <li>{@link #UNKNOWN_OUTCOME}: the request may have reached the provider and there is no usable
 *       answer (5xx, a timeout or a broken connection after the request was written). The message
 *       may have been accepted, charged and delivered, so it is never resent automatically.</li>
 *   <li>{@link #RECIPIENT_NOT_SUPPORTED} / {@link #MESSAGE_REQUIRED}: the request itself is
 *       unusable ({@link IllegalArgumentException}).</li>
 * </ul>
 *
 * <p>{@code PatientNotificationRoutingGuardTest} holds every gateway to these keys.
 */
public final class SmsFailureKeys {

    public static final String REJECTED = "sms.provider.rejected";
    public static final String UNKNOWN_OUTCOME = "sms.provider.unknownOutcome";
    public static final String AUTH_FAILED = "sms.provider.authFailed";
    public static final String UNREACHABLE = "sms.provider.unreachable";
    public static final String RECIPIENT_NOT_SUPPORTED = "sms.recipient.notSupported";
    public static final String MESSAGE_REQUIRED = "sms.message.required";

    /** Every key a send failure can carry. */
    public static final List<String> ALL =
        List.of(REJECTED, UNKNOWN_OUTCOME, AUTH_FAILED, UNREACHABLE, RECIPIENT_NOT_SUPPORTED, MESSAGE_REQUIRED);

    private SmsFailureKeys() {
    }

    /** The key of an HTTP error status the provider answered a send with. */
    public static String ofStatus(int status) {
        if (status == 401 || status == 403) {
            return AUTH_FAILED;
        }
        return status >= 500 ? UNKNOWN_OUTCOME : REJECTED;
    }
}
