package com.example.hms.payload.dto.imaging;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderPriority;
import com.example.hms.enums.ImagingOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Summary of a recent imaging order that may be a duplicate.")
public class ImagingOrderDuplicateMatchDTO {

    private UUID orderId;

    private ImagingModality modality;

    private String bodyRegion;

    private String studyType;

    private ImagingOrderPriority priority;

    private ImagingOrderStatus status;

    private LocalDateTime orderedAt;

    private LocalDateTime scheduledAt;
}
