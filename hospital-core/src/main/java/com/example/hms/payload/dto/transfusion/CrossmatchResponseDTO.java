package com.example.hms.payload.dto.transfusion;

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
public class CrossmatchResponseDTO {

    private UUID id;
    private UUID requestId;
    private UUID bloodUnitId;
    private String unitNumber;
    private Boolean compatible;
    private String method;
    private String incompatibilityReason;
    private String performedByName;
    private LocalDateTime performedAt;
    private LocalDateTime expiresAt;
    /** Compatible AND unexpired — what the issue path actually requires. */
    private Boolean usable;
}
