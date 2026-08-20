package com.example.hms.service.integration;

import java.util.List;
import java.util.Locale;

/**
 * Thin client over the IKODDI (KREEZUS) B2B API used for SMS one-time passwords
 * and transactional SMS — the practical delivery channel for the many patients
 * who have a phone number but no email address. Only the OTP-as-a-service and
 * bulk-SMS endpoints are wired; airtime/WhatsApp are out of scope.
 *
 * <p>IKODDI authenticates with an {@code x-api-key} header and addresses an
 * organisation (group) by id. Phone identities are the international number
 * <em>without</em> the leading {@code +} (e.g. {@code 22670707070}); the
 * implementation derives that from the E.164-style numbers HMS passes in.
 */
public interface IkoddiGateway {

    /** Delivery channels IKODDI can route an OTP through. */
    enum OtpChannel {
        SMS,
        EMAIL,
        WHATSAPP;

        /** Lowercase wire token used in the OTP request path ({@code .../otp/{appId}/{type}/{identity}}). */
        public String wireToken() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** {@code true} when the integration is enabled AND api-key, organization-id and otp-app-id are configured. */
    boolean isConfigured();

    /**
     * Ask IKODDI to send an OTP to {@code identityE164} over {@code channel}.
     *
     * @return the dispatch outcome, including the opaque {@code otpToken} (verification key)
     *     that must be presented back to {@link #verifyOtp} to confirm the code.
     */
    OtpDispatch sendOtp(String identityE164, OtpChannel channel);

    /**
     * Verify a user-entered {@code code} against a prior {@link #sendOtp} dispatch.
     *
     * @param verificationKey the {@code otpToken} returned by {@link #sendOtp}.
     */
    OtpVerification verifyOtp(String identityE164, String code, String verificationKey);

    /** Send a transactional SMS to one or more E.164 recipients. */
    SmsDispatch sendSms(List<String> recipientsE164, String message, String campaignName);

    /** Outcome of an OTP send. IKODDI returns {@code status == 0} on success. */
    record OtpDispatch(int status, String otpToken) {
        public boolean accepted() {
            return status == 0 && otpToken != null && !otpToken.isBlank();
        }
    }

    /** Outcome of an OTP verify. IKODDI returns {@code status == 0} when the code matches. */
    record OtpVerification(int status, String message) {
        public boolean matched() {
            return status == 0;
        }
    }

    /** Outcome of a transactional SMS send. */
    record SmsDispatch(boolean delivered, String rawResponse) {}
}
