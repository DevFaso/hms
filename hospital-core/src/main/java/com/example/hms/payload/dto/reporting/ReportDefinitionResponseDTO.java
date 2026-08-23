package com.example.hms.payload.dto.reporting;

import com.example.hms.enums.ReportPeriod;
import com.example.hms.enums.ReportType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** One scheduled-report definition as the admin sees it (P3 #25a). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReportDefinitionResponseDTO {

    private UUID id;
    private UUID hospitalId;
    private String name;
    private ReportType reportType;
    private ReportPeriod period;
    private String recipients;
    private boolean active;
    private String createdBy;
    private LocalDateTime createdAt;
}
