package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
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

    private LocalDateTime shiftStart;

    private LocalDateTime shiftEnd;

    private List<String> coveringFor;

    private String backupProvider;
}
