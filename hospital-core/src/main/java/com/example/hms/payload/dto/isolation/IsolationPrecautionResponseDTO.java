package com.example.hms.payload.dto.isolation;

import com.example.hms.enums.IsolationPrecautionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IsolationPrecautionResponseDTO {

    private UUID id;
    private UUID patientId;
    private String patientName;
    private UUID admissionId;

    private IsolationPrecautionType precautionType;
    private String reason;
    private String suspectedOrganism;

    private LocalDateTime startedAt;
    private String orderedByName;

    private LocalDateTime endedAt;
    private String discontinuedByName;
    private String discontinuationReason;

    /** Derived on read: {@code endedAt == null}. */
    private boolean active;

    /** Derived on read: only AIRBORNE constrains where the patient may lie. */
    private boolean requiresIsolationWard;

    private String notes;
}
