package com.example.hms.service.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.enums.ProviderVerificationStatus;
import com.example.hms.payload.dto.provider.ProviderCreateRequestDTO;
import com.example.hms.payload.dto.provider.ProviderDecisionRequestDTO;
import com.example.hms.payload.dto.provider.ProviderResubmitRequestDTO;
import com.example.hms.payload.dto.provider.ProviderResponseDTO;
import com.example.hms.payload.dto.provider.ProviderVerifyRequestDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Super-admin onboarding of an external provider facility (provider plan
 * US-1, AC-1 to AC-4). Every method requires a VERIFIED super-admin (a live
 * SUPER_ADMIN assignment, not the token's authority) and answers anyone else
 * with an access refusal.
 *
 * <p>A provider is created SUSPENDED with {@code active=false}; VERIFY is the
 * only way it becomes ACTIVE, and the generic hospital-lifecycle restore
 * refuses a provider whose current verification is not VERIFIED.
 */
public interface ProviderOnboardingService {

    /**
     * The suspension reason of a provider waiting for VERIFY (new, or brought
     * back from an archive unverified); a code, not prose.
     */
    String PENDING_VERIFICATION_REASON = "PROVIDER_PENDING_VERIFICATION";

    /** AC-1: a SUSPENDED, inactive facility and a SUBMITTED verification. */
    ProviderResponseDTO create(ProviderCreateRequestDTO request);

    /** The provider facilities with their current verification; {@code type} and {@code status} narrow it. */
    Page<ProviderResponseDTO> list(FacilityType type, ProviderVerificationStatus status, Pageable pageable);

    /** One provider facility; an id that is not a provider answers as an unknown one. */
    ProviderResponseDTO get(UUID providerId);

    /** AC-2, AC-3: both consistency confirmations, no duplicate business; the facility becomes ACTIVE. */
    ProviderResponseDTO verify(UUID providerId, ProviderVerifyRequestDTO request);

    /** AC-2: the SUBMITTED evidence is refused; the facility stays SUSPENDED. */
    ProviderResponseDTO reject(UUID providerId, ProviderDecisionRequestDTO request);

    /** AC-2: new evidence after a rejection or a revocation, as a new SUBMITTED verification. */
    ProviderResponseDTO resubmit(UUID providerId, ProviderResubmitRequestDTO request);

    /** Rule 2: a VERIFIED provider is revoked; the facility is suspended again. */
    ProviderResponseDTO revoke(UUID providerId, ProviderDecisionRequestDTO request);
}
