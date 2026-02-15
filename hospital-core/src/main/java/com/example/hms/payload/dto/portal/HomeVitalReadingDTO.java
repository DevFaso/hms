package com.example.hms.payload.dto.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Patient-reported vital reading from home (e.g. blood pressure cuff, thermometer, glucometer)")
public class HomeVitalReadingDTO {

    @Schema(description = "Body temperature in Celsius", example = "36.8")
    private Double temperatureCelsius;

    @Min(20) @Max(300)
    @Schema(description = "Heart rate in BPM", example = "72")
    private Integer heartRateBpm;

    @Min(4) @Max(80)
    @Schema(description = "Respiratory rate in breaths per minute", example = "16")
    private Integer respiratoryRateBpm;

    @Min(40) @Max(300)
    @Schema(description = "Systolic blood pressure (mmHg)", example = "120")
    private Integer systolicBpMmHg;

    @Min(20) @Max(200)
    @Schema(description = "Diastolic blood pressure (mmHg)", example = "80")
    private Integer diastolicBpMmHg;

    @Min(0) @Max(100)
    @Schema(description = "Oxygen saturation percentage", example = "98")
    private Integer spo2Percent;

    @Min(20) @Max(800)
    @Schema(description = "Blood glucose level (mg/dL)", example = "95")
    private Integer bloodGlucoseMgDl;

    @DecimalMin("1.0") @DecimalMax("400.0")
    @Schema(description = "Weight in kg", example = "72.5")
    private Double weightKg;

    @Size(max = 40)
    @Schema(description = "Body position during measurement", example = "Sitting")
    private String bodyPosition;

    @Size(max = 1000)
    @Schema(description = "Optional notes", example = "Measured after morning walk")
    private String notes;

    @PastOrPresent
    @Schema(description = "When the reading was taken (defaults to now if omitted)")
    private LocalDateTime recordedAt;
}
