package com.example.hms.payload.dto.reporting;

import com.example.hms.enums.ReportRunStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** One emission of one report period (P3 #25a). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReportRunResponseDTO {

    private UUID id;
    private String periodToken;
    private ReportRunStatus status;
    private Integer rowCount;
    private String errorMessage;
    private LocalDateTime generatedAt;
    private LocalDateTime createdAt;
}
