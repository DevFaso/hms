package com.example.hms.payload.dto.insurance;

import com.example.hms.enums.EligibilityCheckType;
import com.example.hms.enums.EligibilityScheme;
import com.example.hms.enums.EligibilityStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persistent eligibility / prior-auth result exposed on the API.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Persisted outcome of a real-time eligibility / prior-auth call.")
public class EligibilityResponseDTO {

    private UUID id;
    private UUID patientId;
    private UUID hospitalId;
    private UUID patientInsuranceId;

    private EligibilityScheme scheme;
    private EligibilityCheckType checkType;

    private String memberId;
    private String serviceCode;

    private LocalDateTime requestedAt;
    private LocalDateTime completedAt;

    private EligibilityStatus status;

    private String responseCode;
    private String payerResponseText;

    private BigDecimal copayAmount;
    private String copayCurrency;

    private Boolean priorAuthRequired;
    private String priorAuthNumber;
    private LocalDate validUntil;

    private String errorMessage;
    private UUID requestedByUserId;
}
