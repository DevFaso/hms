package com.example.hms.payload.dto.imaging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImagingReportMeasurementRequestDTO {

    private UUID id;

    private Integer sequenceNumber;

    private String label;

    private String region;

    private String plane;

    private String modifier;

    private BigDecimal numericValue;

    private String textValue;

    private String unit;

    private BigDecimal referenceMin;

    private BigDecimal referenceMax;

    private Boolean abnormal;

    private String notes;
}
