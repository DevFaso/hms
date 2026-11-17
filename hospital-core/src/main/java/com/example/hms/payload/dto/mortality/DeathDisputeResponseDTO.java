package com.example.hms.payload.dto.mortality;

import com.example.hms.enums.DeathDisputeStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A dispute, for the doctor and the reviewers. It carries nothing of the death record: another hospital's doctor
 * sees no more of it than the guard's refusal already shows. {@code deathRecordId} is set only for a reader at the
 * hospital that recorded the death, who alone can mark that record entered in error.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeathDisputeResponseDTO {

    private UUID id;
    private UUID patientId;
    private String patientName;
    private String patientMrn;
    private UUID hospitalId;
    private DeathDisputeStatus status;
    private String reason;
    private String disputedByName;
    private LocalDateTime disputedAt;
    private String resolutionReason;
    private String resolvedByName;
    private LocalDateTime resolvedAt;
    private UUID deathRecordId;
    /** "Death status disputed, care permitted pending review", in the reader's language, while open. */
    private String statusMessage;
}
