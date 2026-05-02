package com.example.hms.service.integration.eligibility;

import com.example.hms.enums.EligibilityScheme;

/**
 * SPI plugged in once per public-payer scheme (NHIS, CNAMGS, mutuelle, …).
 *
 * <p>Real connectors that hit the partner network are deferred (P1 #12
 * follow-up #4); current implementations are deterministic stubs that
 * unblock the FE/BE wiring without making outbound calls. Each provider is
 * a Spring bean and the {@link com.example.hms.service.EligibilityService}
 * resolves the right one at request time via {@link #supports(EligibilityScheme)}.
 */
public interface EligibilityProvider {

    /** True when this provider is responsible for the given scheme. */
    boolean supports(EligibilityScheme scheme);

    /** Coverage / member-status check (analogue: X12 270/271). */
    EligibilityProviderResult checkCoverage(EligibilityProviderRequest request);

    /** Prior-authorisation request for a specific service (analogue: X12 278). */
    EligibilityProviderResult requestPriorAuth(EligibilityProviderRequest request);
}
