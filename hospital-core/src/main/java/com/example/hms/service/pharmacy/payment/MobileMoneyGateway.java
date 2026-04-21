package com.example.hms.service.pharmacy.payment;

import java.math.BigDecimal;

/**
 * T-42: abstract interface for mobile-money providers (Orange Money, Wave, MTN MoMo,
 * Moov Money, Airtel Money...). Implementations charge a patient's mobile-money
 * wallet and return a provider-specific transaction reference.
 *
 * <p>The MVP ships a mock adapter that always succeeds and returns a deterministic
 * reference. Real providers are added by creating additional {@link MobileMoneyGateway}
 * beans and selecting one by {@link #providerCode()}.
 */
public interface MobileMoneyGateway {

    /**
     * Unique short code identifying the provider (e.g. {@code "MOCK"}, {@code "ORANGE"},
     * {@code "WAVE"}, {@code "MTN"}). Used for bean selection and logging.
     */
    String providerCode();

    /**
     * Initiate a charge for the given amount against the customer's mobile-money account.
     * Implementations may be synchronous (returns when the provider confirms) or
     * asynchronous (returns a pending reference; the webhook confirms later).
     *
     * @throws MobileMoneyException on provider-level failure (declined, invalid number,
     *         network error). Callers should treat this as a business error, not a
     *         programming bug.
     */
    MobileMoneyCharge charge(MobileMoneyChargeRequest request) throws MobileMoneyException;

    /** Input to {@link #charge(MobileMoneyChargeRequest)}. */
    record MobileMoneyChargeRequest(
            String customerPhone,
            BigDecimal amount,
            String currency,
            String description,
            String idempotencyKey) {
    }

    /** Result of a successful (or pending) charge. */
    record MobileMoneyCharge(
            String providerReference,
            String status,
            BigDecimal chargedAmount,
            String currency) {
    }

    /** Provider-level failure that callers should surface as a business error. */
    class MobileMoneyException extends RuntimeException {
        public MobileMoneyException(String message) {
            super(message);
        }

        public MobileMoneyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
