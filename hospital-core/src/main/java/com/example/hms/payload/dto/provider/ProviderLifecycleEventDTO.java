package com.example.hms.payload.dto.provider;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * QA D4313: one lifecycle or onboarding event of a provider facility, from
 * the audit log: what happened, when and who did it. The event is a code the
 * portal labels; no free text is carried.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "One lifecycle or onboarding event of a provider facility.")
public class ProviderLifecycleEventDTO {

    /** An {@code AuditEventType} name: HOSPITAL_SUSPENDED, HOSPITAL_RESTORED, PROVIDER_VERIFIED… */
    private String event;
    private LocalDateTime occurredAt;
    private String actorName;
}
