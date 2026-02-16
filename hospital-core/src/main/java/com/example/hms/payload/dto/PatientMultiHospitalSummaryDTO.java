package com.example.hms.payload.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Represents a patient with registrations across multiple hospitals.")
public class PatientMultiHospitalSummaryDTO {

    @Schema(description = "Patient identifier", example = "4f8506e4-c607-4f26-b848-2f1b24b561ed")
    UUID patientId;

    @Schema(description = "Concatenated patient name", example = "Salif Nignan")
    String patientName;

    @Schema(description = "Hospital identifier", example = "1a5676cf-4563-4a9b-aaf7-af46c8456ced")
    UUID hospitalId;

    @Schema(description = "Hospital display name", example = "Kaya Regional Hospital")
    String hospitalName;
}
