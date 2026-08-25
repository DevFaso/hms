package com.example.hms.payload.dto.mortality;

import com.example.hms.enums.MannerOfDeath;
import com.example.hms.enums.MaternalDeathTiming;
import com.example.hms.enums.PerinatalDeathType;
import com.example.hms.enums.PlaceOfDeath;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeathRecordResponseDTO {

    private UUID id;
    private UUID patientId;
    private String patientName;
    private String patientMrn;
    private LocalDate patientDateOfBirth;
    private UUID hospitalId;

    private LocalDateTime diedAt;
    private PlaceOfDeath placeOfDeath;
    private MannerOfDeath mannerOfDeath;

    private String immediateCause;
    private String immediateCauseCode;
    private String underlyingCause;
    private String underlyingCauseCode;
    private String contributingCauses;

    private Boolean maternalDeath;
    private MaternalDeathTiming maternalDeathTiming;
    /**
     * A maternal death by the WHO definition. False for a LATE_MATERNAL death,
     * which falls outside it and is reported separately — counting it in would
     * overstate the facility ratio.
     */
    private Boolean whoMaternalDeath;

    private Boolean perinatalDeath;
    private PerinatalDeathType perinatalType;

    private Boolean autopsyRequested;
    private String certifiedByName;
    private LocalDateTime certifiedAt;

    private Boolean amended;
    private LocalDateTime amendedAt;
    private String amendmentReason;

    private String notes;
    private String recordedByName;
    private LocalDateTime createdAt;
}
