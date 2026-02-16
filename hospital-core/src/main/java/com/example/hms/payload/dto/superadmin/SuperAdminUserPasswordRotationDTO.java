package com.example.hms.payload.dto.superadmin;

import com.example.hms.enums.PasswordRotationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuperAdminUserPasswordRotationDTO {

    private UUID userId;
    private String username;
    private String email;
    private String firstName;
    private String lastName;

    private boolean forcePasswordChange;
    private LocalDateTime passwordChangedAt;
    private LocalDateTime passwordRotationWarningAt;
    private LocalDateTime passwordRotationForcedAt;

    private LocalDate rotationDueOn;
    private LocalDate warningStartsOn;

    private long passwordAgeDays;
    private long daysUntilDue;

    private PasswordRotationStatus status;
}
