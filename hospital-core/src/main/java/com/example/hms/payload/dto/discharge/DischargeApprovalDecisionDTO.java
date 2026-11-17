package com.example.hms.payload.dto.discharge;

import com.example.hms.validation.OnApproval;
import com.example.hms.validation.OnRefusal;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
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

    /**
     * QA D1160 class: a refused discharge says why. QA D1372 (round 31):
     * required at the edge on the reject path, so {@code {}} names it with the
     * doctor ids instead of one request later (the service keeps its check).
     */
    @NotBlank(groups = OnRefusal.class, message = "{dischargeApproval.reject.reasonRequired}")
    @Size(max = 4000)
    private String rejectionReason;

    /**
     * QA D766: an approval discharges the stay, so it states the diagnosis as
     * the direct discharge does. QA D1372 class: required at the edge on the
     * approve path, named with the doctor ids.
     */
    @NotBlank(groups = OnApproval.class, message = "{admissionDischarge.dischargeDiagnosis.required}")
    @Size(max = 2000)
    private String dischargeDiagnosis;
}
