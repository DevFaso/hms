package com.example.hms.payload.dto.assignment;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserRoleAssignmentPublicViewDTO {
    private UUID assignmentId;
    private String assignmentCode;
    private String roleName;
    private String roleCode;
    private String roleDescription;
    private String hospitalName;
    private String hospitalCode;
    private String hospitalAddress;
    private String assigneeName;
    private boolean confirmationVerified;
    private LocalDateTime confirmationVerifiedAt;
    private String profileCompletionUrl;
    private List<String> profileChecklist;
}
