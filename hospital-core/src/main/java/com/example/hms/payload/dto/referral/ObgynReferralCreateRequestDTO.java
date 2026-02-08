package com.example.hms.payload.dto.referral;

import com.example.hms.enums.ObgynReferralCareContext;
import com.example.hms.enums.ObgynReferralUrgency;
import com.example.hms.enums.ObgynTransferType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObgynReferralCreateRequestDTO {
    @NotNull
    private UUID patientId;

    @NotNull
    private UUID hospitalId;

    private Integer gestationalAgeWeeks;

    @NotNull
    private ObgynReferralCareContext careContext;

    @NotBlank
    @Size(max = 4000)
    private String referralReason;

    @Size(max = 4000)
    private String clinicalIndication;

    @NotNull
    private ObgynReferralUrgency urgency;

    @Size(max = 4000)
    private String historySummary;

    @Builder.Default
    private boolean ongoingMidwiferyCare = true;

    @NotNull
    private ObgynTransferType transferType;

    @Builder.Default
    @Valid
    private List<ReferralAttachmentUploadDTO> attachments = List.of();

    @Builder.Default
    private boolean generateLetter = true;
}
