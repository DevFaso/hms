package com.example.hms.payload.dto;

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
public class LabResultTrendPointDTO {

    private String labResultId;
    private String labOrderCode;
    private LocalDateTime resultDate;
    private String resultValue;
    private String resultUnit;
    private String severityFlag;
}
