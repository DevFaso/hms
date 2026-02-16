package com.example.hms.payload.dto.assignment;

import com.example.hms.payload.dto.UserRoleHospitalAssignmentResponseDTO;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Collections;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserRoleAssignmentBatchResponseDTO {

    @Builder.Default
    private int requestedAssignments = 0;

    @Builder.Default
    private int createdAssignments = 0;

    @Builder.Default
    private int skippedAssignments = 0;

    @Builder.Default
    private List<UserRoleHospitalAssignmentResponseDTO> assignments = Collections.emptyList();

    @Builder.Default
    private List<UserRoleAssignmentFailureDTO> failures = Collections.emptyList();
}
