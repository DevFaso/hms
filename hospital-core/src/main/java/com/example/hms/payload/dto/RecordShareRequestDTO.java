package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request payload for sharing patient records between hospitals.")
public class RecordShareRequestDTO {

    @Schema(description = "Unique ID of the patient to be shared.", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID patientId;

    @Schema(description = "ID of the source hospital.", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID fromHospitalId;

    @Schema(description = "ID of the target hospital to share with.", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID toHospitalId;
}
