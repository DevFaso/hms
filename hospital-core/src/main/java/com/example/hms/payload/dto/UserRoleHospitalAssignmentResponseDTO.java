package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserRoleHospitalAssignmentResponseDTO {
    private UUID id;
    private String assignmentCode;

    private UUID userId;
    private String userEmail;
    private String userName;

    private UUID hospitalId;
    private String hospitalName;
    private String hospitalCode;
    private String hospitalAddress;
    private String hospitalPhone;
    private String hospitalLicenseNumber;

    private UUID roleId;
    private String roleName;
    private String roleCode;
    private String code;
    private boolean active;
    private LocalDateTime assignedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private LocalDate startDate;

    private LocalDateTime confirmationSentAt;
    private LocalDateTime confirmationVerifiedAt;
    private boolean confirmationVerified;

    private UUID registeredByUserId;
    private String registeredByUserName;
    private String registeredByUserPhone;

    private String profileCompletionUrl;
    private String assignerConfirmationUrl;
    private List<String> profileChecklist;
}

