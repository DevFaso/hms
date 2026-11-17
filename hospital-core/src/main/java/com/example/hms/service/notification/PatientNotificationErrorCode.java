package com.example.hms.service.notification;

import com.example.hms.exception.NotificationTransportUnavailableException;
import com.example.hms.service.integration.SmsRecipientNotAllowedException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;

/**
 * The fixed codes a delivery row carries (V312), and how a send failure maps to one. A transport
 * exception's message can quote the number or the address, so it is never stored or logged: only
 * the code. The SMS gateways throw {@link IllegalStateException}s carrying a message key
 * ({@code sms.provider.*}); those keys are fixed text, so they are read to pick the code.
 *
 * <p>{@link #retryable()} is the bounded-retry rule:
 * <ul>
 *   <li>retried with backoff: nothing reached the provider, or the provider was down
 *       ({@link #PROVIDER_UNREACHABLE}, {@link #PROVIDER_AUTH_FAILED},
 *       {@link #TRANSPORT_UNAVAILABLE}, {@link #MAIL_SEND_FAILED});</li>
 *   <li>never retried automatically: {@link #OUTCOME_UNKNOWN}, because the provider may have
 *       accepted and charged the message, and an automatic resend could text the patient twice
 *       (the gateway itself never resends one, for the same reason); a refused or malformed
 *       message, which fails the same way every time.</li>
 * </ul>
 */
public enum PatientNotificationErrorCode {
    /** The provider refused the message (4xx). */
    PROVIDER_REJECTED(false),
    /** The provider refused the credentials. Nothing was sent. */
    PROVIDER_AUTH_FAILED(true),
    /** The provider could not be reached for a token. Nothing was sent. */
    PROVIDER_UNREACHABLE(true),
    /** The provider gave no usable answer: the message may have gone out. Not resent automatically. */
    OUTCOME_UNKNOWN(false),
    /** No SMS or mail transport on this deployment right now. */
    TRANSPORT_UNAVAILABLE(true),
    /** The number is not one the gateway can text (not E.164, outside the supported countries). */
    RECIPIENT_NOT_SUPPORTED(false),
    /** The mail could not be built (bad address syntax, broken MIME). */
    INVALID_ADDRESS(false),
    /** The SMTP conversation failed. */
    MAIL_SEND_FAILED(true),
    /** Any other failure. Retried: an unexpected error is more often passing than permanent. */
    SEND_FAILED(true),
    /** Every attempt was used. */
    ATTEMPTS_EXHAUSTED(false),

    // Reasons a row is SKIPPED (terminal, not a failure).
    /** This environment's SMS_RECIPIENT_ALLOWLIST does not list the number (dev, uat). */
    RECIPIENT_NOT_ALLOWED(false),
    /** The patient turned this channel off for this kind of notice. */
    OPTED_OUT(false),
    /** The patient no longer has a number (SMS) or an address (email). */
    NO_CONTACT(false),
    /** The appointment was cancelled or has passed, or the result was voided. */
    SUBJECT_WITHDRAWN(false),
    /** The patient has died: no notice is sent to their phone or address. */
    PATIENT_DECEASED(false);

    private final boolean retryable;

    PatientNotificationErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    /** The code of a failed send. Reads the exception's type and, for gateway keys, its fixed key. */
    public static PatientNotificationErrorCode of(RuntimeException failure) {
        if (failure instanceof SmsRecipientNotAllowedException) {
            return RECIPIENT_NOT_ALLOWED;
        }
        if (failure instanceof NotificationTransportUnavailableException) {
            return TRANSPORT_UNAVAILABLE;
        }
        if (failure instanceof MailPreparationException || failure instanceof MailParseException) {
            return INVALID_ADDRESS;
        }
        if (failure instanceof org.springframework.mail.MailException) {
            return MAIL_SEND_FAILED;
        }
        String key = failure.getMessage();
        if (key != null) {
            switch (key) {
                case "sms.provider.rejected":
                    return PROVIDER_REJECTED;
                case "sms.provider.authFailed":
                    return PROVIDER_AUTH_FAILED;
                case "sms.provider.unreachable":
                    return PROVIDER_UNREACHABLE;
                case "sms.provider.unknownOutcome":
                    return OUTCOME_UNKNOWN;
                case "sms.recipient.notSupported":
                    return RECIPIENT_NOT_SUPPORTED;
                default:
                    break;
            }
        }
        if (failure instanceof IllegalArgumentException) {
            return RECIPIENT_NOT_SUPPORTED;
        }
        return SEND_FAILED;
    }
}
