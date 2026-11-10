package com.example.hms.payload.dto.pharmacy;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * G15: {@code POST /pharmacy/dispense/{id}/hand-over}. Both fields optional;
 * the prepared quantity cannot change here (cancel and prepare again).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HandOverRequestDTO {

    /** The patient's wristband, scanned at the counter: checked, never overridable. */
    @Size(max = 255)
    private String patientScanValue;

    /** Appended to the fill's notes. */
    @Size(max = 1000)
    private String notes;

    /**
     * QA D1563 / D1555: the reason for going ahead although the patient's current allergies stop this
     * drug, after reading the allergy card ({@code CurrentAllergyRecheck}). Required only then; kept
     * on the record and audited.
     */
    @Size(max = 500)
    private String allergyAcknowledgementReason;

    /** QA D1563 / D1555: the token the allergy card carried ({@code exceptionKey}): which allergy was acknowledged. */
    @Size(max = 200)
    private String allergyAcknowledgementToken;
}
