package com.example.hms.payload.dto.isolation;

import com.example.hms.enums.IsolationPrecautionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Start a precaution. {@code startedAt} is the server's, never the caller's. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IsolationPrecautionRequestDTO {

    @NotNull
    private UUID patientId;

    /** Optional: precautions legitimately start before there is an admission. */
    private UUID admissionId;

    @NotNull
    private IsolationPrecautionType precautionType;

    @NotNull
    @Size(max = 500)
    private String reason;

    @Size(max = 120)
    private String suspectedOrganism;

    private UUID orderedByStaffId;

    @Size(max = 1000)
    private String notes;
}
