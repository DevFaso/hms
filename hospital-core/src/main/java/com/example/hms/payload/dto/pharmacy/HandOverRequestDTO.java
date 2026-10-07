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
}
