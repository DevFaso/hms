package com.example.hms.payload.dto.superadmin;

import com.example.hms.enums.platform.PlatformReleaseStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformReleaseWindowResponseDTO {
    private UUID id;
    private String name;
    private String description;
    private String environment;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private PlatformReleaseStatus status;
    private boolean freezeChanges;
    private String ownerTeam;
    private String notes;
    /** Why the window was cancelled; null while it is not. */
    private String cancelReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
