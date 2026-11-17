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

    /** Required, named at once with the other fields; the service also bounds its length. */
    @jakarta.validation.constraints.NotBlank
    private String reason;

    /** The person making the decision, picked from the options the server offers. */
    @NotNull
    private UUID authoriserUserId;

    /**
     * For O positive when the system shows O negative in stock: the person states it cannot be
     * obtained in time. Required then, on its field; ignored otherwise.
     */
    private Boolean negativeUnobtainable;

    /** An uncrossmatched release documents nothing: named at the edge with the other fields. */
    @jakarta.validation.constraints.AssertTrue(message = "{transfusion.emergency.typeNotDocumented}")
    @com.example.hms.validation.ReportedOn("releaseType")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isReleaseTypeDocumented() {
        return releaseType != EmergencyReleaseType.UNCROSSMATCHED;
    }
}
