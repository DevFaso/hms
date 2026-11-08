package com.example.hms.payload.dto.highrisk;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HighRiskBloodPressureLogRequestDTO {

    @NotNull
    private LocalDate readingDate;

    @NotNull
    @Min(60)
    @Max(260)
    private Integer systolic;

    @NotNull
    @Min(30)
    @Max(180)
    private Integer diastolic;

    @Min(30)
    @Max(220)
    private Integer heartRate;

    @Size(max = 500)
    private String notes;

    /** QA D1424 (D111): a diastolic at or above the systolic is a swapped entry. */
    @com.example.hms.validation.ReportedOn("diastolic")
    @jakarta.validation.constraints.AssertTrue(message = "{vitals.bloodPressure.invalid}")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isBloodPressureOrdered() {
        return com.example.hms.payload.dto.VitalSignLimits.bloodPressureOrdered(systolic, diastolic);
    }
}
