package com.example.hms.payload.dto.portal;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Patient-facing diagnosis summary")
public class PatientDiagnosisSummaryDTO {

    private UUID id;
    private String description;
    private String icdCode;
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
    private OffsetDateTime diagnosedAt;

    private String diagnosedByName;
}
