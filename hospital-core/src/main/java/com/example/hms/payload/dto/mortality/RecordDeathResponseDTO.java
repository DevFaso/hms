package com.example.hms.payload.dto.mortality;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The certificate plus an account of what its recording closed. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordDeathResponseDTO {

    private DeathRecordResponseDTO deathRecord;
    private DeathClosureSummaryDTO closure;
}
