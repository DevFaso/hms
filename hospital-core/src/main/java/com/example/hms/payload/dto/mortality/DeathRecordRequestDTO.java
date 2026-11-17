package com.example.hms.payload.dto.mortality;

import com.example.hms.enums.MannerOfDeath;
import com.example.hms.enums.MaternalDeathTiming;
import com.example.hms.enums.PerinatalBirthStatus;
import com.example.hms.enums.PerinatalDeathType;
import com.example.hms.enums.PlaceOfDeath;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Recording a death. The recorder is the authenticated caller and the hospital
 * comes from their active scope — neither is accepted from the body.
 *
 * <p>The certifier IS accepted, because the clinician who certifies a death is
 * frequently not the clerk entering it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeathRecordRequestDTO {

    @NotNull
    private UUID patientId;

    @NotNull
    private LocalDateTime diedAt;

    private PlaceOfDeath placeOfDeath;

    private MannerOfDeath mannerOfDeath;

    /** What finally stopped the heart. */
    @NotBlank
    @Size(max = 500)
    private String immediateCause;

    @Size(max = 20)
    private String immediateCauseCode;

    /**
     * The disease that set the sequence going — the one mortality statistics
     * actually count. An immediate cause of "cardiac arrest" on its own tells
     * the register nothing.
     */
    @Size(max = 500)
    private String underlyingCause;

    @Size(max = 20)
    private String underlyingCauseCode;

    @Size(max = 1000)
    private String contributingCauses;

    private Boolean maternalDeath;

    /** Required when maternalDeath is true — the service refuses it otherwise. */
    private MaternalDeathTiming maternalDeathTiming;

    private Boolean perinatalDeath;

    /** Required when perinatalDeath is true. */
    private PerinatalDeathType perinatalType;

    /** The time of birth, when known. With it the age at death is elapsed time; without it, only dates. */
    private LocalDateTime bornAt;

    /** Left off or UNKNOWN when not known: a single-infant delivery on file may then answer. */
    private PerinatalBirthStatus birthStatus;

    /**
     * Completed weeks. Left off when not known, and the death still saves. Deliberately no bean-validation bound:
     * that would refuse the death itself with no classification selected. DeathClassificationRules judges it.
     */
    private Integer gestationalAgeWeeks;

    /**
     * QA D1714: the fetal-death event this stillbirth declares, at the acting hospital. Left empty, a stillbirth of a
     * baby registered from a delivery links to that baby's event when there is one. The register counts it once.
     */
    private java.util.UUID fetalDeathEventId;

    private Boolean autopsyRequested;

    /** The clinician certifying, who is often not the person entering this. */
    private UUID certifiedByStaffId;

    @Size(max = 1000)
    private String notes;
}
