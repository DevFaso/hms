package com.example.hms.payload.dto.nurse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Bedside scan payload sent by the eMAR UI when running the five-rights check
 * against a {@link com.example.hms.model.MedicationAdministrationRecord}.
 *
 * <p>All five values are required because the verification service refuses to
 * pass a right that was not observed at the bedside — silently passing a
 * missing scan would defeat the purpose of the safety loop.
 */
@Data
public class MarVerificationRequestDTO {

    /** Wristband barcode value (typically the patient UUID). */
    @NotBlank(message = "Patient wristband scan is required.")
    @Size(max = 255)
    private String patientScanValue;

    /** Medication label barcode (formulary code, RxNorm, or medication name). */
    @NotBlank(message = "Medication barcode scan is required.")
    @Size(max = 255)
    private String medicationScanValue;

    /** Dose entered or scanned at the bedside (e.g. "500 mg"). */
    @NotBlank(message = "Dose is required.")
    @Size(max = 100)
    private String doseScanValue;

    /** Route entered or scanned at the bedside (e.g. "PO", "IV"). */
    @NotBlank(message = "Route is required.")
    @Size(max = 80)
    private String routeScanValue;

    /**
     * Time the dose was actually administered. Optional — server falls back to
     * {@code now()} if omitted, which is the typical real-time scan case.
     */
    private LocalDateTime administeredAt;
}
