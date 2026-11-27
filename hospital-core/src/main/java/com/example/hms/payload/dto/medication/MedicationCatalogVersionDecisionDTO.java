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

    /**
     * Required for a refusal ({@link com.example.hms.validation.OnRefusal}) and for submitting an entry as it
     * stands, which creates a version awaiting approval ({@link com.example.hms.validation.OnCreate}); optional
     * on an approval. Each handler validates its own group, so the body is refused at the edge, by field.
     */
    @jakarta.validation.constraints.NotBlank(groups = com.example.hms.validation.OnRefusal.class,
        message = "{medication.catalog.version.rejectionReasonRequired}")
    @jakarta.validation.constraints.NotBlank(groups = com.example.hms.validation.OnCreate.class,
        message = "{medication.catalog.version.reasonRequired}")
    @Size(max = 1000)
    private String reason;
}
