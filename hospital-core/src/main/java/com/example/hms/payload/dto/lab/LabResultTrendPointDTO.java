package com.example.hms.payload.dto.lab;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LabResultTrendPointDTO {
    private String value;
    private LocalDateTime collectedAt;
    private LocalDateTime resultedAt;
    private String status;
    private boolean abnormal;
}
