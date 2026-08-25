package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.RhFactor;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

/** A unit received into the facility from a supplier or regional blood bank. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BloodUnitRequestDTO {

    @NotBlank
    @Size(max = 60)
    private String unitNumber;

    @NotNull
    private BloodProductType productType;

    @NotNull
    private AboGroup aboGroup;

    @NotNull
    private RhFactor rhFactor;

    @Min(1)
    private Integer volumeMl;

    private LocalDate collectedOn;

    @NotNull
    private LocalDate expiresOn;

    @Size(max = 200)
    private String source;

    /** Set when the unit was obtained for a specific request. */
    private UUID requestId;

    @Size(max = 1000)
    private String notes;
}
