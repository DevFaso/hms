package com.example.hms.payload.dto.imaging;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Shared base class for imaging report measurement fields,
 * eliminating duplication between request and response DTOs.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class ImagingReportMeasurementBaseDTO {

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
