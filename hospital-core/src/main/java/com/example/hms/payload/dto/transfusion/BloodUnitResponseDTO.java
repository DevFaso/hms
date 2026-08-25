package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.BloodUnitStatus;
import com.example.hms.enums.RhFactor;
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
public class BloodUnitResponseDTO {

    private UUID id;
    private UUID hospitalId;
    private UUID requestId;
    private String unitNumber;
    private BloodProductType productType;
    private AboGroup aboGroup;
    private RhFactor rhFactor;
    private Integer volumeMl;
    private LocalDate collectedOn;
    private LocalDate expiresOn;
    /** Computed against today, not stored — a unit does not expire when observed. */
    private Boolean expired;
    private String source;
    private BloodUnitStatus status;
    private String discardReason;
    private String notes;
    private LocalDateTime createdAt;
}
