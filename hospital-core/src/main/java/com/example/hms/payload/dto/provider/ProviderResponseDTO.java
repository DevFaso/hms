package com.example.hms.payload.dto.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.enums.HospitalLifecycleState;
import com.example.hms.enums.ProviderVerificationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/** A provider facility and its current verification, for the super-admin providers screens. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A provider facility and its current verification.")
public class ProviderResponseDTO {

    /** The facility's id (a {@code hospital.hospitals} row). */
    private UUID id;
    private FacilityType facilityType;
    private String code;
    private String name;
    private String email;
    private boolean active;
    private HospitalLifecycleState lifecycleState;

    private UUID verificationId;
    private ProviderVerificationStatus verificationStatus;
    private ProviderBusinessIdentityDTO business;
    private ProviderProfessionalDTO professional;
    /**
     * QA D4072: true once a verifier confirmed the IFU document agrees with
     * the RCCM extract; null while nobody has recorded that check. Never
     * false: a mismatch is a refusal to verify, not a stored answer.
     */
    private Boolean ifuMatchesRccm;
    /** QA D4072: as {@link #ifuMatchesRccm}, for the CNSS document. */
    private Boolean cnssMatchesRccm;
    /**
     * QA D4073: why a SUSPENDED facility is suspended: PENDING_VERIFICATION
     * (never verified), VERIFICATION_REVOKED, or OPERATOR (a lifecycle
     * suspension). Null when the facility is not suspended.
     */
    private String suspensionCause;
    private String evidenceNote;
    private UUID decidedByUserId;
    /** QA D4313: who decided, by name; null when undecided or the account is gone. */
    private String decidedByName;
    private LocalDateTime decidedAt;
    private String decisionReason;
    private LocalDateTime submittedAt;
}
