package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for doctor-accessible patient record view.")
public class DoctorPatientRecordRequestDTO {

    @Schema(description = "Hospital context for the record request.")
    private UUID hospitalId;

    @NotBlank
    @Schema(description = "Free-form justification stored with the audit log.", example = "Pre-op review for tomorrow's procedure")
    private String accessReason;

    @Schema(description = "Whether to include sensitive data in the response. Defaults to false.")
    private Boolean includeSensitiveData;

    @Schema(description = "Optional cap applied to clinical collections (labs, imaging, notes). Defaults to 50 entries per section, max 200.")
    private Integer maxItems;

    @Schema(description = "Optional override for note entries. Inherits from maxItems when not provided.")
    private Integer notesLimit;
}
