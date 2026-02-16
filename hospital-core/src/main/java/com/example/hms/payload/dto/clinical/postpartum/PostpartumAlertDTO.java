package com.example.hms.payload.dto.clinical.postpartum;

import com.example.hms.enums.PostpartumAlertSeverity;
import com.example.hms.enums.PostpartumAlertType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostpartumAlertDTO {

    private PostpartumAlertType type;
    private PostpartumAlertSeverity severity;
    private String code;
    private String message;
    private String triggeredBy;
    private LocalDateTime createdAt;
}
