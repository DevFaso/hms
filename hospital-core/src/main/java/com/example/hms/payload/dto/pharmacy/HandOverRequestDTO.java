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
     * Policy rule 6 (V345; QA D2675): the deferral somebody else approved for this prescription, on which a fill
     * not paid (nor covered by a claim) leaves « à facturer ». The pharmacist executes it; they do not grant it.
     */
    private java.util.UUID billingDeferralId;

    /**
     * Policy rule 6: the reason of a logged « urgence » override, when the fill cannot wait for an approval. Only a
     * pharmacist or a hospital admin may give it, and finance reviews it afterwards. Never card data.
     */
    @Size(max = 500)
    @com.example.hms.validation.NoCardNumber
    private String billingUrgencyReason;
}
