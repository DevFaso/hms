package com.example.hms.service.integration;

/**
 * QA D1842: a send refused because the recipient is not on this environment's
 * {@link SmsRecipientAllowlist}. Not a transport failure — nothing was sent and
 * nothing is broken — so a caller that reports delivery outcomes can tell the
 * registrar "this number is not allowed here" instead of "the send failed".
 *
 * <p>Still an {@link IllegalStateException} carrying
 * {@link SmsRecipientAllowlist#KEY_NOT_ALLOWLISTED} as its message, so every
 * caller that already translated that key keeps working.
 */
public class SmsRecipientNotAllowedException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public SmsRecipientNotAllowedException() {
        super(SmsRecipientAllowlist.KEY_NOT_ALLOWLISTED);
    }
}
