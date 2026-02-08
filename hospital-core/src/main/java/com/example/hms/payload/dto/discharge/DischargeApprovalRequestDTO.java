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
 * Nurse-initiated request to start a discharge approval.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DischargeApprovalRequestDTO {

    @NotNull
    private UUID registrationId;

    @NotNull
    private UUID nurseStaffId;

    @NotNull
    private UUID nurseAssignmentId;

    @Size(max = 4000)
    private String nurseSummary;
}
