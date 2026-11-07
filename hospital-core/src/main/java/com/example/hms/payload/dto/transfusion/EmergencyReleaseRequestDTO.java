package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.EmergencyReleaseType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** A documented emergency release. Who issues it is the caller; who answers for it is named here. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmergencyReleaseRequestDTO {

    @NotNull
    private UUID bloodUnitId;

    /** O_POSITIVE_DECISION or SEROLOGICALLY_INCOMPATIBLE. An uncrossmatched release documents nothing. */
    @NotNull
    private EmergencyReleaseType releaseType;

    /** Required; checked by the service, which names the field in the caller's language. */
    private String reason;

    /** Required: the person making the decision, picked from the options the server offers. */
    private UUID authoriserUserId;
}
