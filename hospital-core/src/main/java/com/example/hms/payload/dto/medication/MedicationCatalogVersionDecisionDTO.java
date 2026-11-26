package com.example.hms.payload.dto.medication;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Go-live D2 (V385): the words that go with a step of the medication catalogue's approval circuit: why an entry
 * is submitted as it stands, a note on an approval, or the reason of a refusal (required there).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedicationCatalogVersionDecisionDTO {

    @Size(max = 1000)
    private String reason;
}
