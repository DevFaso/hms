package com.example.hms.payload.dto.registry;

import jakarta.validation.constraints.PastOrPresent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** Record that a programme visit happened (Tier 2 item 35). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProgramVisitDTO {

    /**
     * When the patient was seen. Optional; today when absent. Never the
     * future — this records a visit that happened, it does not book one.
     */
    @PastOrPresent
    private LocalDate visitDate;
}
