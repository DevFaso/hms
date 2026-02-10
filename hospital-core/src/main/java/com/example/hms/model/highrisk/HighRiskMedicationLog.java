package com.example.hms.model.highrisk;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tracks medication adherence for patients enrolled in a high-risk pregnancy program.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Embeddable
public class HighRiskMedicationLog {

    @Column(name = "med_log_id", nullable = false)
    @Builder.Default
    private UUID logId = UUID.randomUUID();

    @Size(max = 120)
    @Column(name = "medication_name", length = 120, nullable = false)
    private String medicationName;

    @Size(max = 60)
    @Column(name = "dosage", length = 60)
    private String dosage;

    @Column(name = "taken", nullable = false)
    @Builder.Default
    private Boolean taken = Boolean.TRUE;

    @Column(name = "taken_at", nullable = false)
    private LocalDateTime takenAt;

    @Size(max = 500)
    @Column(name = "notes", length = 500)
    private String notes;
}
