package com.example.hms.service.integration.eligibility;

import com.example.hms.enums.EligibilityStatus;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Provider-side outcome of a coverage / prior-auth call.
 *
 * <p>Keeping this DTO immutable lets us cache the result in the EligibilityService
 * without worrying about post-publish mutation and makes provider stubs trivial.
 */
@Value
@Builder
public class EligibilityProviderResult {

    EligibilityStatus status;

    /** Scheme-specific code, e.g. NHIS "ACT" / "EXP" / "REJ". */
    String responseCode;

    String payerResponseText;

    BigDecimal copayAmount;

    String copayCurrency;

    Boolean priorAuthRequired;

    String priorAuthNumber;

    LocalDate validUntil;

    String errorMessage;
}
