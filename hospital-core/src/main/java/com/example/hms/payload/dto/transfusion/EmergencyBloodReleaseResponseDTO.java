package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.EmergencyReleaseType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/** One bag's emergency release, as the request detail shows it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmergencyBloodReleaseResponseDTO {

    private UUID id;
    private UUID bloodUnitId;
    private String unitNumber;
    private EmergencyReleaseType releaseType;
    private String reason;
    private String authoriserName;
    private String issuedByName;
    private LocalDateTime issuedAt;
    private boolean antibodyWarning;
    /** O negative showed in stock and was stated not obtainable in time. */
    private boolean negativeUnobtainableStated;
    /** The O negative bags shown then, by the number on the bag. */
    private String negativeUnitsShown;
}
