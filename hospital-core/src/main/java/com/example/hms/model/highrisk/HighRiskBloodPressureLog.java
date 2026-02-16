package com.example.hms.model.highrisk;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Captures a single home blood-pressure reading logged for a high-risk pregnancy.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Embeddable
public class HighRiskBloodPressureLog {

    @Column(name = "log_id", nullable = false)
    @Builder.Default
    private UUID logId = UUID.randomUUID();

    @Column(name = "reading_date", nullable = false)
    private LocalDate readingDate;

    @Min(60)
    @Max(260)
    @Column(name = "systolic", nullable = false)
    private Integer systolic;

    @Min(30)
    @Max(180)
    @Column(name = "diastolic", nullable = false)
    private Integer diastolic;

    @Min(30)
    @Max(220)
    @Column(name = "heart_rate")
    private Integer heartRate;

    @Size(max = 500)
    @Column(name = "notes", length = 500)
    private String notes;
}
