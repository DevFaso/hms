package com.example.hms.payload.dto;

import com.example.hms.enums.TreatmentConsentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/** Records a consent-to-treat (P3 #21). A record, not a gate. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TreatmentConsentRequestDTO {

    @NotNull
    private TreatmentConsentMethod method;

    /** The name as signed/typed at the desk. */
    @Size(max = 200)
    private String signedName;

    /** Optional link to the visit being consented to. */
    private UUID appointmentId;

    private UUID encounterId;

    private LocalDateTime expiresAt;

    @Size(max = 500)
    private String notes;
}
