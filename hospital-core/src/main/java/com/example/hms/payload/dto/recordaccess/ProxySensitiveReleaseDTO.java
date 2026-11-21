package com.example.hms.payload.dto.recordaccess;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Policy 2026-11-20: one release of a minor's sensitive result to a parent's
 * or guardian's grant, as the clinicians read it back - to whom, when, and
 * the reason recorded. Never sent to a proxy.
 */
@Getter
@AllArgsConstructor
public class ProxySensitiveReleaseDTO {

    /** The family-access grant the result was released to. */
    private final UUID grantId;

    /** The holder of that grant; null when the grant is no longer active. */
    private final String holderName;

    private final LocalDateTime releasedAt;

    /** What the clinician recorded when releasing the result. */
    private final String reason;
}
