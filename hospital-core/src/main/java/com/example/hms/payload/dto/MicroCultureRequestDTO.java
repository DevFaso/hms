package com.example.hms.payload.dto;

import com.example.hms.enums.MicroGrowthResult;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/** Creates a culture report on an existing lab order (P3 #19). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MicroCultureRequestDTO {

    @NotNull
    private UUID labOrderId;

    /** Optional link to the accessioned specimen the culture was plated from. */
    private UUID specimenId;

    @Size(max = 100)
    private String specimenSource;

    @PastOrPresent
    private LocalDateTime collectedAt;

    @Size(max = 255)
    private String gramStain;

    /** May stay null while the culture is pending; required to finalize. */
    private MicroGrowthResult growthResult;

    @Size(max = 1000)
    private String notes;
}
