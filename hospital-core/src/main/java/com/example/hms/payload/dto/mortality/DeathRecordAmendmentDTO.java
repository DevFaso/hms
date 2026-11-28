package com.example.hms.payload.dto.mortality;

import com.example.hms.enums.MannerOfDeath;
import com.example.hms.enums.MaternalDeathTiming;
import com.example.hms.enums.PerinatalBirthStatus;
import com.example.hms.enums.PerinatalDeathType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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

    /** Recognised only to refuse attempts to change the recorded time explicitly. */
    private LocalDateTime diedAt;

    /** Mandatory. An amended certificate with no account of why is worse than none. */
    @NotBlank(message = "{mortality.amendmentReasonRequired}")
    @Size(max = 500, message = "{validation.reason.size}")
    private String amendmentReason;

    @Size(max = 500, message = "{mortality.validation.causeTooLong}")
    private String immediateCause;

    @Size(max = 20, message = "{mortality.validation.causeCodeTooLong}")
    private String immediateCauseCode;

    @Size(max = 500, message = "{mortality.validation.causeTooLong}")
    private String underlyingCause;

    @Size(max = 20, message = "{mortality.validation.causeCodeTooLong}")
    private String underlyingCauseCode;

    @Size(max = 1000, message = "{mortality.validation.contributingTooLong}")
    private String contributingCauses;

    private MannerOfDeath mannerOfDeath;

    private Boolean maternalDeath;

    private MaternalDeathTiming maternalDeathTiming;

    private Boolean perinatalDeath;

    private PerinatalDeathType perinatalType;

    /** The time of birth, when known. With it the age at death is elapsed time; without it, only dates. */
    private LocalDateTime bornAt;

    /** Left off keeps what the record holds. */
    private PerinatalBirthStatus birthStatus;

    /** Completed weeks. Left off keeps what the record holds. */
    private Integer gestationalAgeWeeks;

    @Size(max = 1000, message = "{validation.notes.size1000}")
    private String notes;
}
