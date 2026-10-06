package com.example.hms.exception;

/**
 * The SMS provider refused a prescription dispatch.
 *
 * <p>A {@link BusinessException} so the caller still gets the same 400 with a
 * readable message, but its own type because the dispatch transaction
 * deliberately does NOT roll back on it: the FAILED transmission row and the
 * prescription's TRANSMISSION_FAILED status are the record of the failure,
 * and rolling them back left nothing but a log line.
 *
 * <p>The message is a bundle key, never the provider's text, which can quote
 * the destination number.
 */
public class PrescriptionDispatchFailedException extends BusinessException {

    public PrescriptionDispatchFailedException(String messageKey) {
        super(messageKey);
    }
}
