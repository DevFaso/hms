package com.example.hms.payload.dto.superadmin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmergencyActionResponseDTO {

    private String action;
    private Instant takenAt;
    private String actorUsername;
    private int affectedRows;
    private String message;
}
