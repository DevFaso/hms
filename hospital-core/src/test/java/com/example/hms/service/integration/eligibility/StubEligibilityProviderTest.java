package com.example.hms.service.integration.eligibility;

import com.example.hms.enums.EligibilityScheme;
import com.example.hms.enums.EligibilityStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the deterministic behaviour of {@link StubEligibilityProvider} so the
 * FE/BE round-trip is reproducible without partner connectivity. Once the real
 * NHIS / CNAMGS / mutuelle connectors land (P1 #12 follow-up #4), this test
 * stays in place to verify the fallback for any scheme that has not yet
 * shipped a connector.
 */
@DisplayName("StubEligibilityProvider")
class StubEligibilityProviderTest {

    private final StubEligibilityProvider provider = new StubEligibilityProvider();

    private EligibilityProviderRequest request(EligibilityScheme scheme, String memberId) {
        return EligibilityProviderRequest.builder()
            .patientId(UUID.randomUUID())
            .hospitalId(UUID.randomUUID())
            .scheme(scheme)
            .memberId(memberId)
            .build();
    }

    private EligibilityProviderRequest priorAuthRequest(EligibilityScheme scheme,
                                                        String memberId,
                                                        String serviceCode) {
        return EligibilityProviderRequest.builder()
            .patientId(UUID.randomUUID())
            .hospitalId(UUID.randomUUID())
            .scheme(scheme)
            .memberId(memberId)
            .serviceCode(serviceCode)
            .build();
    }

    @Test
    @DisplayName("supports every scheme — acts as the fallback provider")
    void supportsAllSchemes() {
        for (EligibilityScheme scheme : EligibilityScheme.values()) {
            assertThat(provider.supports(scheme)).isTrue();
        }
        assertThat(provider.supports(null)).isFalse();
    }

    @Test
    @DisplayName("checkCoverage: missing memberId yields ERROR + MISSING_MEMBER_ID")
    void coverageMissingMember() {
        EligibilityProviderResult result = provider.checkCoverage(request(EligibilityScheme.NHIS_GH, "  "));
        assertThat(result.getStatus()).isEqualTo(EligibilityStatus.ERROR);
        assertThat(result.getResponseCode()).isEqualTo("MISSING_MEMBER_ID");
        assertThat(result.getErrorMessage()).contains("memberId");
    }

    @Test
    @DisplayName("checkCoverage: memberId starting with X is NOT_ELIGIBLE")
    void coverageInactive() {
        EligibilityProviderResult result = provider.checkCoverage(request(EligibilityScheme.NHIS_GH, "X1234"));
        assertThat(result.getStatus()).isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
        assertThat(result.getResponseCode()).isEqualTo("INACTIVE");
    }

    @Test
    @DisplayName("checkCoverage: memberId ending with ? is UNKNOWN (payer timeout)")
    void coverageUnknown() {
        EligibilityProviderResult result = provider.checkCoverage(request(EligibilityScheme.CNAMGS_GA, "12345?"));
        assertThat(result.getStatus()).isEqualTo(EligibilityStatus.UNKNOWN);
        assertThat(result.getResponseCode()).isEqualTo("PAYER_TIMEOUT");
    }

    @Test
    @DisplayName("checkCoverage: NHIS_GH ELIGIBLE returns GHS currency + 30-day validity")
    void coverageEligibleNhis() {
        EligibilityProviderResult result = provider.checkCoverage(request(EligibilityScheme.NHIS_GH, "NHIS-998877"));
        assertThat(result.getStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(result.getCopayCurrency()).isEqualTo("GHS");
        assertThat(result.getValidUntil()).isNotNull();
        assertThat(result.getPriorAuthRequired()).isTrue();
    }

    @Test
    @DisplayName("checkCoverage: MUTUELLE_RW ELIGIBLE → RWF currency, prior_auth_required=false")
    void coverageEligibleMutuelleRw() {
        EligibilityProviderResult result = provider.checkCoverage(request(EligibilityScheme.MUTUELLE_RW, "RW-123"));
        assertThat(result.getStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(result.getCopayCurrency()).isEqualTo("RWF");
        assertThat(result.getPriorAuthRequired()).isFalse();
    }

    @Test
    @DisplayName("requestPriorAuth: short-circuits when coverage is NOT_ELIGIBLE")
    void priorAuthShortCircuitsOnNotEligible() {
        EligibilityProviderResult result = provider.requestPriorAuth(
            priorAuthRequest(EligibilityScheme.NHIS_GH, "X-INACTIVE", "CT-HEAD"));
        assertThat(result.getStatus()).isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
        assertThat(result.getPriorAuthNumber()).isNull();
    }

    @Test
    @DisplayName("requestPriorAuth: missing serviceCode after eligible coverage → ERROR")
    void priorAuthRequiresServiceCode() {
        EligibilityProviderResult result = provider.requestPriorAuth(
            priorAuthRequest(EligibilityScheme.NHIS_GH, "OK-123", "  "));
        assertThat(result.getStatus()).isEqualTo(EligibilityStatus.ERROR);
        assertThat(result.getResponseCode()).isEqualTo("MISSING_SERVICE_CODE");
    }

    @Test
    @DisplayName("requestPriorAuth: ELIGIBLE coverage + serviceCode returns deterministic auth number")
    void priorAuthApproved() {
        EligibilityProviderResult first = provider.requestPriorAuth(
            priorAuthRequest(EligibilityScheme.NHIS_GH, "OK-123", "CT-HEAD"));
        EligibilityProviderResult second = provider.requestPriorAuth(
            priorAuthRequest(EligibilityScheme.NHIS_GH, "OK-123", "CT-HEAD"));
        assertThat(first.getStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(first.getPriorAuthNumber()).startsWith("PA-");
        assertThat(first.getPriorAuthNumber()).isEqualTo(second.getPriorAuthNumber());
        assertThat(first.getValidUntil()).isAfter(java.time.LocalDate.now());
    }
}
