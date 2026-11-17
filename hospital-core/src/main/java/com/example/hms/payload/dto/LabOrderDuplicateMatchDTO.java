package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An active lab order of the same test for the same patient at the same
 * hospital, placed recently: the new order may be a duplicate of it. The
 * shape mirrors {@code ImagingOrderDuplicateMatchDTO}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Summary of a recent active lab order of the same test that may be a duplicate.")
public class LabOrderDuplicateMatchDTO {

    private UUID orderId;

    private String labOrderCode;

    private String labTestName;

    private String labTestCode;

    private String priority;

    private String status;

    private LocalDateTime orderDatetime;
}
