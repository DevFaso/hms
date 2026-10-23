package com.example.hms.payload.dto.pharmacy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * QA D654: {@code POST /pharmacy/dispense/{id}/cancel}. The body is optional;
 * the witness is required when the cancel puts a controlled substance back.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DispenseCancelRequestDTO {

    /** The second person who witnessed the controlled stock going back on the shelf. */
    private UUID witnessedBy;
}
