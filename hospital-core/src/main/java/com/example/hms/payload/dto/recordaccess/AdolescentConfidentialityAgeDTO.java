package com.example.hms.payload.dto.recordaccess;

import java.util.UUID;

/**
 * Policy 2026-11-20 (provisional): a hospital's adolescent confidentiality
 * age. From {@code effectiveAge} to 17, a minor's sensitive records are hidden
 * from the parents unless a clinician releases them.
 *
 * @param configuredAge what the hospital set; null when it set none
 * @param effectiveAge  the age that applies: the configured one, else the default
 * @param defaultAge    the provisional default, pending a jurist's confirmation
 * @param provisional   the rule is provisional
 */
public record AdolescentConfidentialityAgeDTO(
    UUID hospitalId,
    Integer configuredAge,
    int effectiveAge,
    int defaultAge,
    boolean provisional
) {
}
