package com.example.hms.payload.dto.mortality;

import com.example.hms.enums.MannerOfDeath;
import com.example.hms.enums.MaternalDeathTiming;
import com.example.hms.enums.PerinatalDeathType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Revising a cause of death.
 *
 * <p>An autopsy or a coroner routinely changes the cause weeks later, so this
 * exists rather than the record being frozen. It deliberately cannot touch
 * {@code diedAt} or the patient: the FACT of death is not amendable through a
 * form, only the account of it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeathRecordAmendmentDTO {

    /** Mandatory. An amended certificate with no account of why is worse than none. */
    @NotBlank
    @Size(max = 500)
    private String amendmentReason;

    @Size(max = 500)
    private String immediateCause;

    @Size(max = 20)
    private String immediateCauseCode;

    @Size(max = 500)
    private String underlyingCause;

    @Size(max = 20)
    private String underlyingCauseCode;

    @Size(max = 1000)
    private String contributingCauses;

    private MannerOfDeath mannerOfDeath;

    private Boolean maternalDeath;

    private MaternalDeathTiming maternalDeathTiming;

    private Boolean perinatalDeath;

    private PerinatalDeathType perinatalType;

    @Size(max = 1000)
    private String notes;
}
