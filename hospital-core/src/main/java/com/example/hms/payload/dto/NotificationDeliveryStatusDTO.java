package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-channel outcome of one activation-related notification attempt,
 * surfaced to the REGISTRAR on the registration / resend responses.
 *
 * <p>Before this existed, every delivery failure was swallowed into a WARN
 * log while the API returned a green 200 — on a deployment with no mail or
 * SMS transport configured, a receptionist could register patients all day
 * without ever learning that no activation message went anywhere.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationDeliveryStatusDTO {

    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String CHANNEL_SMS = "SMS";

    public static final String PURPOSE_ACTIVATION = "ACTIVATION";
    public static final String PURPOSE_CREDENTIALS = "CREDENTIALS";
    public static final String PURPOSE_WELCOME = "WELCOME";

    public static final String OUTCOME_SENT = "SENT";
    public static final String OUTCOME_FAILED = "FAILED";
    /** The transport itself is absent/disabled on this deployment. */
    public static final String OUTCOME_NOT_CONFIGURED = "NOT_CONFIGURED";
    /** The mock SMS channel accepted the message but no phone receives it. */
    public static final String OUTCOME_MOCKED = "MOCKED";
    /** The account has no address/number for this channel — informational. */
    public static final String OUTCOME_NO_CONTACT = "NO_CONTACT";

    /** {@link #CHANNEL_EMAIL} or {@link #CHANNEL_SMS}. */
    private String channel;

    /** What the message carried: ACTIVATION code, one-time CREDENTIALS, or WELCOME mail. */
    private String purpose;

    /** One of the OUTCOME_* constants. */
    private String outcome;

    /** Masked recipient (never the full address/number). */
    private String target;

    /** Optional operator hint (English, not meant as primary UI copy). */
    private String detail;
}
