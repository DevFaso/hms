package com.example.hms.payload.dto.reporting;

import com.example.hms.enums.ReportPeriod;
import com.example.hms.enums.ReportType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Create a scheduled-report definition (P3 #25a). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReportDefinitionRequestDTO {

    @NotBlank(message = "{reportDefinition.name.required}")
    @Size(max = 150)
    private String name;

    @NotNull(message = "{reportDefinition.reportType.required}")
    private ReportType reportType;

    @NotNull(message = "{reportDefinition.period.required}")
    private ReportPeriod period;

    /** Comma-separated recipient email addresses. */
    @NotBlank(message = "{reportDefinition.recipients.required}")
    @Size(max = 1000)
    private String recipients;
}
