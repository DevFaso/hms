package com.example.hms.payload.dto.superadmin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One hourly bucket for the integration-health 24h sparkline
 * (MVP-c batch — MVP-3b).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Hourly success/failure counts for the integration-health sparkline.")
public class IntegrationHistoryBucketDTO {
    private LocalDateTime bucketStart;
    private long successCount;
    private long failureCount;
}
