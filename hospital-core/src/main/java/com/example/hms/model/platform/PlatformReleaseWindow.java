package com.example.hms.model.platform;

import com.example.hms.enums.platform.PlatformReleaseStatus;
import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "platform_release_windows",
    schema = "platform",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_release_window_name", columnNames = {"window_name", "environment"})
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class PlatformReleaseWindow extends BaseEntity {

    @NotBlank
    @Size(max = 120)
    @Column(name = "window_name", nullable = false, length = 120)
    private String name;

    @Size(max = 240)
    @Column(name = "description", length = 240)
    private String description;

    @NotBlank
    @Size(max = 60)
    @Column(name = "environment", nullable = false, length = 60)
    private String environment;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    @Builder.Default
    private PlatformReleaseStatus status = PlatformReleaseStatus.SCHEDULED;

    @Column(name = "freeze_changes", nullable = false)
    @Builder.Default
    private boolean freezeChanges = false;

    @Size(max = 120)
    @Column(name = "owner_team", length = 120)
    private String ownerTeam;

    @Size(max = 255)
    @Column(name = "notes", length = 255)
    private String notes;
}
