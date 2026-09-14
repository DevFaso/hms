package com.example.hms.payload.dto;

import com.example.hms.enums.ReferralSpecialty;
import com.example.hms.enums.ReferralType;
import com.example.hms.enums.ReferralUrgency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for creating a general referral
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeneralReferralRequestDTO {

    @NotNull(message = "{generalReferral.patientId.required}")
    private UUID patientId;

    @NotNull(message = "{department.hospital.required}")
    private UUID hospitalId;

    private UUID receivingHospitalId;

    private UUID sourceDepartmentId;

    @NotNull(message = "{generalReferral.referringProviderId.required}")
    private UUID referringProviderId;

    private UUID receivingProviderId;

    @NotNull(message = "{generalReferral.targetSpecialty.required}")
    private ReferralSpecialty targetSpecialty;

    private UUID targetDepartmentId;

    private String targetFacilityName;

    @NotNull(message = "{generalReferral.referralType.required}")
    private ReferralType referralType;

    @NotNull(message = "{generalReferral.urgency.required}")
    private ReferralUrgency urgency;

    @NotBlank(message = "{generalReferral.referralReason.required}")
    private String referralReason;

    private String clinicalIndication;

    private String clinicalSummary;

    private List<Map<String, String>> currentMedications;

    private List<Map<String, String>> diagnoses;

    private String clinicalQuestion;

    private String anticipatedTreatment;

    private String insuranceAuthNumber;

    private Map<String, Object> metadata;
}
