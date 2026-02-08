package com.example.hms.payload.dto.imaging;

import com.example.hms.enums.ImagingReportStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImagingReportStatusUpdateRequestDTO {

    private ImagingReportStatus status;

    private String statusReason;

    private UUID changedByStaffId;

    private String changedByName;

    private String clientSource;

    private String notes;
}
