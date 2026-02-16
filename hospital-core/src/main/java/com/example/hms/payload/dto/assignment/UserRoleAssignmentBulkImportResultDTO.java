package com.example.hms.payload.dto.assignment;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserRoleAssignmentBulkImportResultDTO {
    private int rowNumber;
    private String identifier;
    private boolean success;
    private String message;
    private UUID assignmentId;
    private String assignmentCode;
    private UUID hospitalId;
    private UUID organizationId;
    private String hospitalCode;
    private String roleCode;
    private String profileCompletionUrl;
}
