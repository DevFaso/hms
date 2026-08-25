package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.DispenseStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DispenseRequestDTO {

    @NotNull(message = "Prescription ID is required")
    private UUID prescriptionId;

    @NotNull(message = "Patient ID is required")
    private UUID patientId;

    @NotNull(message = "Pharmacy ID is required")
    private UUID pharmacyId;

    private UUID stockLotId;

    @NotNull(message = "Dispensed-by user ID is required")
    private UUID dispensedBy;

    private UUID verifiedBy;

    private UUID medicationCatalogItemId;

    @NotBlank(message = "Medication name is required")
    @Size(max = 255)
    private String medicationName;

    @NotNull(message = "Quantity requested is required")
    private BigDecimal quantityRequested;

    @NotNull(message = "Quantity dispensed is required")
    private BigDecimal quantityDispensed;

    @Size(max = 60)
    private String unit;

    private Boolean substitution;

    @Size(max = 500)
    private String substitutionReason;

    /**
     * P-08: pharmacist's reason for overriding a CRITICAL CDS alert at dispense
     * time. Required when the prospective CDS check returns
     * {@code requiresOverride = true}; otherwise ignored.
     */
    @Size(max = 1000)
    private String cdsOverrideReason;

    private DispenseStatus status;

    @Size(max = 1000)
    private String notes;

    /**
     * Roadmap row 4 / T-68 — optional idempotency key the offline pharmacy
     * queue mints when the user clicks Dispense, so a replayed POST after
     * connectivity returns produces the same DispenseResponseDTO without
     * a second stock decrement / audit / ready-for-pickup SMS. Format is
     * client-defined; the server only checks UNIQUE-ness and length.
     * Omit (null) to opt out — preserves today's create-or-fail behaviour.
     */
    @Size(max = 64)
    private String idempotencyKey;

    /**
     * Tier 2 item 34 — raw value scanned from the patient's wristband,
     * which encodes the bare patient UUID (#475's printed wristband is the
     * scan target).
     *
     * <p>Optional by design. Most sites in this deployment have no scanner
     * and dispense against a paper slip; requiring a scan would take the
     * pharmacy offline rather than make it safer. When it IS supplied the
     * server verifies it and refuses a mismatch — an unverifiable scan is
     * never treated as a pass.
     */
    @Size(max = 255)
    private String patientScanValue;

    /**
     * Tier 2 item 34 — raw value scanned from the stock lot's printed label
     * ({@code "LOT-" + 12 hex}, minted by the server in V138).
     *
     * <p>Also optional, and for the same reason. Note that the checks it
     * strengthens — that the lot is the prescribed drug and is in date —
     * run whether or not anybody scans, because both are answerable from
     * the lot the pharmacist already named.
     */
    @Size(max = 255)
    private String productScanValue;
}
