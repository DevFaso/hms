package com.example.hms.payload.dto.imaging;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ImagingReportMeasurementRequestDTO extends ImagingReportMeasurementBaseDTO {
    // All fields inherited from base; no request-specific fields needed
}
