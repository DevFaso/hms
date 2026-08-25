package com.example.hms.payload.dto.imaging;

import com.example.hms.enums.ImagingReportStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Voids a report administratively (CANCELLED / ERROR) and says why.
 *
 * <p>{@code changedByStaffId} and {@code changedByName} used to live here, so
 * the client named who made the change and the status-history row recorded
 * whatever it was told. The service now resolves the actor from the
 * authenticated caller, and those fields are gone rather than left as an
 * ignored surface that reads like it still works.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImagingReportStatusUpdateRequestDTO {

    @NotNull
    private ImagingReportStatus status;

    /** Required by the service: a voided report with no account of why is a hole in the chart. */
    @Size(max = 500)
    private String statusReason;

    /** Free-text provenance of the change (e.g. "PACS sync", "reading room"). Not an identity. */
    @Size(max = 100)
    private String clientSource;

    @Size(max = 1000)
    private String notes;
}
