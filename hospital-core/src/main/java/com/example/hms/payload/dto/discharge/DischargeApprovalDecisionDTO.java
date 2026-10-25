package com.example.hms.payload.dto.discharge;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Doctor decision payload for approving or rejecting a discharge request.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DischargeApprovalDecisionDTO {

    @NotNull
    private UUID doctorStaffId;

    @NotNull
    private UUID doctorAssignmentId;

    @Size(max = 4000)
    private String doctorNote;

    @Size(max = 4000)
    private String rejectionReason;

    /** QA D766: an approval discharges the stay, so it states the diagnosis as the direct discharge does. */
    @Size(max = 2000)
    private String dischargeDiagnosis;
}
