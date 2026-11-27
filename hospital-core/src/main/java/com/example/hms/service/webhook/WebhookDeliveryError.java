package com.example.hms.service.webhook;

import com.example.hms.utility.MessageUtil;

import java.util.Locale;

/**
 * QA D4078: why a webhook delivery did not arrive, as a cause the reader can act
 * on. The delivery row keeps what the attempt produced (an HTTP status, or the
 * text of the transport failure) for the server side; what leaves the API is one
 * of these causes and its sentence in the reader's language, never the text of a
 * Java exception.
 */
public enum WebhookDeliveryError {
    /** The receiver did not answer in time. */
    TIMEOUT,
    /** The receiver's host name does not resolve. */
    HOST_NOT_FOUND,
    /** Nothing accepted the connection at the receiver's address. */
    CONNECTION_REFUSED,
    /** The secure connection could not be established (certificate, protocol). */
    TLS,
    /** The receiver answered with a client error (4xx): it refused the message. */
    RECEIVER_REFUSED,
    /** The receiver answered with a server error (5xx). */
    RECEIVER_ERROR,
    /** The receiver answered with a redirect or another status that is not a success. */
    UNEXPECTED_STATUS,
    /** Nothing was sent: the address is no longer a public HTTPS address. */
    URL_NOT_PUBLIC,
    /** Nothing was sent: the endpoint was paused, revoked or disabled meanwhile. */
    ENDPOINT_INACTIVE,
    /** The receiver could not be reached, for a network reason not listed above. */
    UNREACHABLE;

    /** The cause of a failed attempt; null when the row records no failure. */
    public static WebhookDeliveryError of(Integer httpStatus, String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        if (stored.startsWith("Refused before sending")) {
            return URL_NOT_PUBLIC;
        }
        if (stored.startsWith("Endpoint is ")) {
            return ENDPOINT_INACTIVE;
        }
        if (httpStatus != null) {
            if (httpStatus >= 500) {
                return RECEIVER_ERROR;
            }
            return httpStatus >= 400 ? RECEIVER_REFUSED : UNEXPECTED_STATUS;
        }
        String text = stored.toLowerCase(Locale.ROOT);
        if (text.contains("timed out") || text.contains("timeout")) {
            return TIMEOUT;
        }
        if (text.contains("unknownhost") || text.contains("unresolved") || text.contains("name or service not known")) {
            return HOST_NOT_FOUND;
        }
        if (text.contains("ssl") || text.contains("pkix") || text.contains("certificate") || text.contains("handshake")) {
            return TLS;
        }
        if (text.contains("connection refused") || text.contains("connectexception")) {
            return CONNECTION_REFUSED;
        }
        return UNREACHABLE;
    }

    /** The sentence for this cause in {@code locale}; the HTTP status is named when there is one. */
    public String describe(Integer httpStatus, Locale locale) {
        String key = "webhook.delivery.error." + name();
        String text = MessageUtil.resolveOrNull(locale, key, new Object[] {httpStatus == null ? "" : String.valueOf(httpStatus)});
        return text != null ? text : name();
    }
}
