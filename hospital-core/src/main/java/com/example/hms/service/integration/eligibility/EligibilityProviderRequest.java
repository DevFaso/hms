package com.example.hms.service.integration.eligibility;

import com.example.hms.enums.EligibilityScheme;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * Inbound payload to an {@link EligibilityProvider}. Designed to be the
 * minimum the partner needs — the rest of the patient context stays inside
 * HMS to limit what the provider integrations can see.
 */
@Value
@Builder
public class EligibilityProviderRequest {

    UUID patientId;

    UUID hospitalId;

    EligibilityScheme scheme;

    /** Member id on the payer card (NHIS card no., CNAMGS no., mutuelle id, …). */
    String memberId;

    /** CPT-equivalent / local procedure code for prior-auth flows. */
    String serviceCode;
}
