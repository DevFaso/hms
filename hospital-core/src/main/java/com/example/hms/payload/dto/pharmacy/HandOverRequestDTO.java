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
     * QA D2675 (r65): why a fill not paid (nor covered by a claim) is handed over anyway: « à facturer » (billed
     * later, on the stay's invoice for instance). Required only then, and only a pharmacist or a hospital admin may
     * give it. Never card data.
     */
    @Size(max = 500)
    @com.example.hms.validation.NoCardNumber
    private String billingDeferredReason;
}
