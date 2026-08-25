package com.example.hms.payload.dto.transfusion;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The serologic verdict on one unit for one request.
 *
 * <p>{@code compatible} is a finding, not a permission: the service re-derives
 * ABO/Rh compatibility and refuses to store {@code true} for a pair the rules
 * reject. A tick box cannot overrule antigen biology.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrossmatchRequestDTO {

    @NotNull
    private UUID bloodUnitId;

    @NotNull
    private Boolean compatible;

    @Size(max = 60)
    private String method;

    @Size(max = 500)
    private String incompatibilityReason;

    /** When the reservation lapses. Defaults to 72h from now when omitted. */
    private LocalDateTime expiresAt;
}
