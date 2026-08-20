package com.example.hms.payload.dto.bed;

import com.example.hms.enums.BedStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Manual bed-status change (maintenance, reserve, back in service).
 * OCCUPIED is not settable here — it only happens through bed assignment.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BedStatusUpdateRequestDTO {

    @NotNull
    private BedStatus status;

    @Size(max = 500)
    private String notes;
}
