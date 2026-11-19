package com.example.hms.payload.dto.imaging;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QA D2464 (r64): a key image withdrawn after signing, as the record keeps it:
 * when, by whom and why. Never the bytes, which no reader can open any more.
 */
@Getter
@AllArgsConstructor
public class ImagingStudyImageWithdrawalDTO {
    private final UUID id;
    private final UUID imagingOrderId;
    private final UUID imagingReportId;
    private final String caption;
    private final LocalDateTime withdrawnAt;
    private final UUID withdrawnByUserId;
    private final String withdrawalReason;
}
