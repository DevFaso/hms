package com.example.hms.service.pharmacy.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * T-42: mock mobile-money gateway used for development and tests. Acts as the
 * default {@link MobileMoneyGateway} implementation when no real provider bean
 * has been registered, so the pharmacy checkout flow works out-of-the-box.
 *
 * <p>Always returns a successful charge with a generated reference, unless the
 * request amount is non-positive or the phone number is blank — in which case
 * it throws {@link MobileMoneyException} just like a real provider would.
 */
@Component
@ConditionalOnMissingBean(value = MobileMoneyGateway.class, ignored = MockMobileMoneyGateway.class)
@Slf4j
public class MockMobileMoneyGateway implements MobileMoneyGateway {

    public static final String PROVIDER_CODE = "MOCK";

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public MobileMoneyCharge charge(MobileMoneyChargeRequest request) {
        if (request == null) {
            throw new MobileMoneyException("charge request is required");
        }
        if (request.customerPhone() == null || request.customerPhone().isBlank()) {
            throw new MobileMoneyException("customer phone is required");
        }
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new MobileMoneyException("amount must be positive");
        }
        String ref = "MOCK-" + UUID.randomUUID();
        // Mask the customer phone to avoid leaking PII to centralized logs.
        log.debug("Mock mobile-money charge: phone={}, amount={} {}, ref={}",
                maskPhone(request.customerPhone()), request.amount(), request.currency(), ref);
        return new MobileMoneyCharge(ref, "COMPLETED", request.amount(), request.currency());
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "****";
        }
        return "***" + phone.substring(phone.length() - 2);
    }
}
