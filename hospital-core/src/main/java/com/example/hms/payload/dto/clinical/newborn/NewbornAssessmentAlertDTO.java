package com.example.hms.payload.dto.clinical.newborn;

import com.example.hms.enums.NewbornAlertSeverity;
import com.example.hms.enums.NewbornAlertType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewbornAssessmentAlertDTO {

    private NewbornAlertType type;
    private NewbornAlertSeverity severity;
    private String code;
    private String message;
    private String triggeredBy;
    private LocalDateTime createdAt;
}
