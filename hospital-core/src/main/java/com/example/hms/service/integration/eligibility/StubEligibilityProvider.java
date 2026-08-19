package com.example.hms.service.integration.eligibility;

import com.example.hms.enums.EligibilityScheme;
import com.example.hms.enums.EligibilityStatus;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/**
 * Deterministic stub implementation that handles every {@link EligibilityScheme}
 * at low priority. {@link Order} = {@code LOWEST_PRECEDENCE} pins the resolution
 * order so a real partner-API connector registered with default precedence wins
 * for its scheme — Spring's {@code List<EligibilityProvider>} injection respects
 * {@code @Order}, so the
 * {@link com.example.hms.service.EligibilityService}'s {@code findFirst}
 * deterministically falls back to this stub only when no scheme-specific
 * provider exists.
 *
 * <p>The stub is deterministic by design: a memberId starting with "X" is
 * NOT_ELIGIBLE, one ending with "?" is UNKNOWN, an empty / null memberId is
 * ERROR (caller forgot to fill the policy in), and everything else is
 * ELIGIBLE. This is enough to demo the FE/BE round-trip and to write
 * deterministic tests, without making outbound network calls. Replace with a
 * real connector in P1 #12 follow-up #4.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class StubEligibilityProvider implements EligibilityProvider {

    /** Default copay currency by scheme — only used when the stub returns ELIGIBLE. */
    private static final java.util.Map<EligibilityScheme, String> CURRENCY_BY_SCHEME = java.util.Map.of(
        EligibilityScheme.NHIS_GH, "GHS",
        EligibilityScheme.NHIA_NG, "NGN",
        EligibilityScheme.CNAMGS_GA, "XAF",
        EligibilityScheme.MUTUELLE_RW, "RWF",
        EligibilityScheme.MUTUELLE_BF, "XOF"
    );

    /** Public schemes routinely require prior auth for non-trivial services. */
    private static final Set<EligibilityScheme> PRIOR_AUTH_REQUIRED_BY_DEFAULT = Set.of(
        EligibilityScheme.NHIS_GH,
        EligibilityScheme.NHIA_NG,
        EligibilityScheme.CNAMGS_GA
    );

    @Override
    public boolean supports(EligibilityScheme scheme) {
        return scheme != null;
    }

    @Override
    public EligibilityProviderResult checkCoverage(EligibilityProviderRequest request) {
        String memberId = request.getMemberId();
        if (memberId == null || memberId.isBlank()) {
            return EligibilityProviderResult.builder()
                .status(EligibilityStatus.ERROR)
                .responseCode("MISSING_MEMBER_ID")
                .errorMessage("memberId is required for a coverage check")
                .build();
        }
        String trimmed = memberId.trim();
        if (trimmed.startsWith("X")) {
            return EligibilityProviderResult.builder()
                .status(EligibilityStatus.NOT_ELIGIBLE)
                .responseCode("INACTIVE")
                .payerResponseText("Coverage inactive or membership lapsed")
                .build();
        }
        if (trimmed.endsWith("?")) {
            return EligibilityProviderResult.builder()
                .status(EligibilityStatus.UNKNOWN)
                .responseCode("PAYER_TIMEOUT")
                .payerResponseText("Payer system did not respond in time")
                .build();
        }
        EligibilityScheme scheme = request.getScheme();
        return EligibilityProviderResult.builder()
            .status(EligibilityStatus.ELIGIBLE)
            .responseCode("ACTIVE")
            .payerResponseText("Coverage active for the requested service date")
            .copayAmount(new BigDecimal("0.00"))
            .copayCurrency(CURRENCY_BY_SCHEME.getOrDefault(scheme, "USD"))
            .priorAuthRequired(PRIOR_AUTH_REQUIRED_BY_DEFAULT.contains(scheme))
            .validUntil(LocalDate.now().plusDays(30))
            .build();
    }

    @Override
    public EligibilityProviderResult requestPriorAuth(EligibilityProviderRequest request) {
        EligibilityProviderResult coverage = checkCoverage(request);
        if (coverage.getStatus() != EligibilityStatus.ELIGIBLE) {
            return coverage;
        }
        if (request.getServiceCode() == null || request.getServiceCode().isBlank()) {
            return EligibilityProviderResult.builder()
                .status(EligibilityStatus.ERROR)
                .responseCode("MISSING_SERVICE_CODE")
                .errorMessage("serviceCode is required for prior-auth")
                .build();
        }
        String authNumber = "PA-" + Integer.toHexString(
            (request.getMemberId() + ":" + request.getServiceCode()).hashCode())
            .toUpperCase();
        return EligibilityProviderResult.builder()
            .status(EligibilityStatus.ELIGIBLE)
            .responseCode("APPROVED")
            .payerResponseText("Prior authorisation approved")
            .priorAuthRequired(Boolean.TRUE)
            .priorAuthNumber(authNumber)
            .copayCurrency(coverage.getCopayCurrency())
            .copayAmount(coverage.getCopayAmount())
            .validUntil(LocalDate.now().plusDays(60))
            .build();
    }
}
