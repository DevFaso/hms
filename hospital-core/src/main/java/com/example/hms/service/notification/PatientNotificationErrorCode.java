package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.exception.NotificationTransportUnavailableException;
import com.example.hms.service.integration.SmsFailureKeys;
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
    /** Any other email failure. Retried. (An unclassified SMS failure is {@link #OUTCOME_UNKNOWN}.) */
    SEND_FAILED(true),
    /** Every attempt was used. */
    ATTEMPTS_EXHAUSTED(false),

    // Reasons a row is SKIPPED (terminal, not a failure).
    /** This environment's SMS_RECIPIENT_ALLOWLIST (or EMAIL_RECIPIENT_ALLOWLIST) does not list the recipient. */
    RECIPIENT_NOT_ALLOWED(false),
    /** The patient turned this channel off for this kind of notice. */
    OPTED_OUT(false),
    /** The patient no longer has a number (SMS) or an address (email). */
    NO_CONTACT(false),
    /** A clinical notice requires proof for the exact current recipient. */
    RECIPIENT_UNVERIFIED(false),
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

    /**
     * The code of a failed send on {@code channel}. Reads the exception's type and, for SMS, the
     * gateway's fixed key ({@link SmsFailureKeys}).
     *
     * <p><b>SMS: an unrecognised failure is {@link #OUTCOME_UNKNOWN}</b>, never retried
     * automatically. Every gateway failure carries a known key
     * ({@code PatientNotificationRoutingGuardTest}); anything else is an exception nobody
     * classified, and it may have come after the provider accepted (and charged) the message.
     * Resending it could text the patient twice; a hospital administrator can still retry it.
     *
     * <p><b>Email</b>: an SMTP {@code MailSendException} is retried ({@link #MAIL_SEND_FAILED}).
     * It can also come after the server accepted the message (a dropped connection while reading
     * the final reply), so a rare duplicate email is accepted here, where it is not for a paid SMS.
     */
    public static PatientNotificationErrorCode of(RuntimeException failure, NotificationChannel channel) {
        if (failure instanceof SmsRecipientNotAllowedException) {
            return RECIPIENT_NOT_ALLOWED;
        }
        if (failure instanceof NotificationTransportUnavailableException) {
            return TRANSPORT_UNAVAILABLE;
        }
        if (channel == NotificationChannel.EMAIL) {
            if (failure instanceof MailPreparationException || failure instanceof MailParseException
                || failure instanceof IllegalArgumentException) {
                return INVALID_ADDRESS;
            }
            return failure instanceof org.springframework.mail.MailException ? MAIL_SEND_FAILED : SEND_FAILED;
        }
        String key = failure.getMessage();
        if (SmsFailureKeys.REJECTED.equals(key)) {
            return PROVIDER_REJECTED;
        }
        if (SmsFailureKeys.AUTH_FAILED.equals(key)) {
            return PROVIDER_AUTH_FAILED;
        }
        if (SmsFailureKeys.UNREACHABLE.equals(key)) {
            return PROVIDER_UNREACHABLE;
        }
        if (SmsFailureKeys.RECIPIENT_NOT_SUPPORTED.equals(key) || SmsFailureKeys.MESSAGE_REQUIRED.equals(key)
            || failure instanceof IllegalArgumentException) {
            return RECIPIENT_NOT_SUPPORTED;
        }
        return OUTCOME_UNKNOWN;
    }
}
