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
    private boolean ifuMatchesRccm;
    private boolean cnssMatchesRccm;
    private String evidenceNote;
    private UUID decidedByUserId;
    private LocalDateTime decidedAt;
    private String decisionReason;
    private LocalDateTime submittedAt;
}
