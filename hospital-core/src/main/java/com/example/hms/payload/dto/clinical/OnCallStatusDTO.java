package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * DTO for On-Call Status
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnCallStatusDTO {

    private Boolean isOnCall;

    private OffsetDateTime shiftStart;

    private OffsetDateTime shiftEnd;

    private List<String> coveringFor;

    private String backupProvider;
}
