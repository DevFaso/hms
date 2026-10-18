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

    // A JSON body that omits a list (or sends null) must mean "no targets of this kind",
    // not a NullPointerException in the service (D32).
    public List<UUID> getUserIds() {
        return userIds == null ? new ArrayList<>() : userIds;
    }

    public List<String> getEmails() {
        return emails == null ? new ArrayList<>() : emails;
    }

    public List<String> getUsernames() {
        return usernames == null ? new ArrayList<>() : usernames;
    }
}
