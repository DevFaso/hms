package com.example.hms.payload.dto.bed;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Payload for assigning a bed to an admission. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BedAssignmentRequestDTO {

    @NotNull
    private UUID bedId;
}
