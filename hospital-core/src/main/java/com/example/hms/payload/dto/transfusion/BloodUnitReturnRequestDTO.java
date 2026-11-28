package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.BloodUnitReturnOutcome;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * QA D4178: the laboratory records what became of a bag it had issued and that was never hung. A bag goes
 * back into stock only on the laboratory's own two statements; anything else takes it out of stock.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BloodUnitReturnRequestDTO {

    @NotNull
    private BloodUnitReturnOutcome outcome;

    /** The laboratory states that the cold chain was kept while the bag was out. Required to restock it. */
    private Boolean coldChainKept;

    /** The laboratory states that the bag came back intact and sealed. Required to restock it. */
    private Boolean bagIntact;

    /** What was found; required when the bag is destroyed or was not recovered. */
    @Size(max = 500)
    private String note;
}
