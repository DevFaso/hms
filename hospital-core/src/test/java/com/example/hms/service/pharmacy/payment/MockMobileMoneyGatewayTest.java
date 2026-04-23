package com.example.hms.service.pharmacy.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-42: mock mobile-money gateway unit tests.
 */
@DisplayName("MockMobileMoneyGateway")
class MockMobileMoneyGatewayTest {

    private final MockMobileMoneyGateway gateway = new MockMobileMoneyGateway();

    @Test
    @DisplayName("reports MOCK provider code")
    void reportsProviderCode() {
        assertThat(gateway.providerCode()).isEqualTo("MOCK");
    }

    @Test
    @DisplayName("returns a successful charge with provider reference and echoed amount")
    void successfulCharge() {
        MobileMoneyGateway.MobileMoneyCharge charge = gateway.charge(
                new MobileMoneyGateway.MobileMoneyChargeRequest(
                        "+22670000000", new BigDecimal("2500"), "XOF", "desc", "idem-1"));

        assertThat(charge.providerReference()).startsWith("MOCK-");
        assertThat(charge.status()).isEqualTo("COMPLETED");
        assertThat(charge.chargedAmount()).isEqualByComparingTo("2500");
        assertThat(charge.currency()).isEqualTo("XOF");
    }

    @Test
    @DisplayName("throws MobileMoneyException for blank phone")
    void rejectsBlankPhone() {
        MobileMoneyGateway.MobileMoneyChargeRequest req =
                new MobileMoneyGateway.MobileMoneyChargeRequest(
                        "", BigDecimal.TEN, "XOF", "d", "i");
        assertThatThrownBy(() -> gateway.charge(req))
                .isInstanceOf(MobileMoneyGateway.MobileMoneyException.class)
                .hasMessageContaining("phone");
    }

    @Test
    @DisplayName("throws MobileMoneyException for non-positive amount")
    void rejectsNonPositiveAmount() {
        MobileMoneyGateway.MobileMoneyChargeRequest req =
                new MobileMoneyGateway.MobileMoneyChargeRequest(
                        "+22670000000", BigDecimal.ZERO, "XOF", "d", "i");
        assertThatThrownBy(() -> gateway.charge(req))
                .isInstanceOf(MobileMoneyGateway.MobileMoneyException.class)
                .hasMessageContaining("amount");
    }

    @Test
    @DisplayName("throws MobileMoneyException for null request")
    void rejectsNullRequest() {
        assertThatThrownBy(() -> gateway.charge(null))
                .isInstanceOf(MobileMoneyGateway.MobileMoneyException.class);
    }

    @Test
    @DisplayName("throws MobileMoneyException for null phone")
    void rejectsNullPhone() {
        MobileMoneyGateway.MobileMoneyChargeRequest req =
                new MobileMoneyGateway.MobileMoneyChargeRequest(
                        null, BigDecimal.TEN, "XOF", "d", "i");
        assertThatThrownBy(() -> gateway.charge(req))
                .isInstanceOf(MobileMoneyGateway.MobileMoneyException.class)
                .hasMessageContaining("phone");
    }

    @Test
    @DisplayName("throws MobileMoneyException for null amount")
    void rejectsNullAmount() {
        MobileMoneyGateway.MobileMoneyChargeRequest req =
                new MobileMoneyGateway.MobileMoneyChargeRequest(
                        "+22670000000", null, "XOF", "d", "i");
        assertThatThrownBy(() -> gateway.charge(req))
                .isInstanceOf(MobileMoneyGateway.MobileMoneyException.class)
                .hasMessageContaining("amount");
    }

    @Test
    @DisplayName("MobileMoneyException supports wrapping a cause")
    void exceptionWrapsCause() {
        RuntimeException cause = new RuntimeException("network down");
        MobileMoneyGateway.MobileMoneyException ex =
                new MobileMoneyGateway.MobileMoneyException("wrapped", cause);

        assertThat(ex.getMessage()).isEqualTo("wrapped");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
