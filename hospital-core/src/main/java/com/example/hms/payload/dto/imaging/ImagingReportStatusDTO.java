package com.example.hms.payload.dto.imaging;

import com.example.hms.enums.ImagingReportStatus;
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
public class ImagingReportStatusDTO {

    private UUID id;

    private ImagingReportStatus status;

    private String statusReason;

    private LocalDateTime changedAt;

    private UUID changedByStaffId;

    private String changedByName;

    private String clientSource;

    private String notes;
}
