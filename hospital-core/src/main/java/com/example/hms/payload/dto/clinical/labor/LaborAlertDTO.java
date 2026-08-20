package com.example.hms.payload.dto.clinical.labor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LaborAlertDTO {

    private String type;
    private String severity;
    private String code;
    private String message;
    private String triggeredBy;
    private LocalDateTime createdAt;
}
