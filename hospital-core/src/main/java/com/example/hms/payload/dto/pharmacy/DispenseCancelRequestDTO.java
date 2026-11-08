package com.example.hms.payload.dto.pharmacy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * QA D654: {@code POST /pharmacy/dispense/{id}/cancel}. The witness is
 * required when the cancel puts a controlled substance back. QA D1069: the
 * reason is always required (the service refuses a missing or blank one with
 * a keyed message): the cancel voids the payment and returns the stock.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DispenseCancelRequestDTO {

    /** The second person who witnessed the controlled stock going back on the shelf. */
    private UUID witnessedBy;

    /**
     * QA D1069: why the fill is cancelled, in the pharmacist's words; kept on
     * the dispense and the voided payment. Blank and over 500 characters are
     * refused by the service, with the limit in the message.
     */
    // QA D1349 (round 30): declared so a blank reason is named at the edge with
    // a malformed witness; the service keeps its own check for a missing body.
    @jakarta.validation.constraints.NotBlank(message = "{pharmacy.dispense.cancelReasonRequired}")
    private String reason;
}
