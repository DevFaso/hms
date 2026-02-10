package com.example.hms.model.highrisk;

import com.example.hms.enums.HighRiskMilestoneType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Represents a scheduled follow-up touchpoint for high-risk pregnancy monitoring.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Embeddable
public class HighRiskMonitoringMilestone {

    @Column(name = "milestone_id", nullable = false)
    @Builder.Default
    private UUID milestoneId = UUID.randomUUID();

    @Enumerated(EnumType.STRING)
    @Column(name = "milestone_type", length = 40, nullable = false)
    private HighRiskMilestoneType type;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(name = "completed", nullable = false)
    @Builder.Default
    private Boolean completed = Boolean.FALSE;

    @Column(name = "completed_at")
    private LocalDate completedAt;

    @Size(max = 120)
    @Column(name = "assigned_to", length = 120)
    private String assignedTo;

    @Size(max = 240)
    @Column(name = "location", length = 240)
    private String location;

    @Size(max = 500)
    @Column(name = "summary", length = 500)
    private String summary;

    @Size(max = 500)
    @Column(name = "follow_up_actions", length = 500)
    private String followUpActions;
}
