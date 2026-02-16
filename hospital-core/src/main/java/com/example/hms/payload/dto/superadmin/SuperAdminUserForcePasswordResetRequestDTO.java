package com.example.hms.payload.dto.superadmin;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuperAdminUserForcePasswordResetRequestDTO {

    @Builder.Default
    private List<UUID> userIds = new ArrayList<>();

    @Builder.Default
    private List<String> emails = new ArrayList<>();

    @Builder.Default
    private List<String> usernames = new ArrayList<>();

    @Builder.Default
    private boolean sendEmail = true;

    @Size(max = 255)
    private String reason;
}
