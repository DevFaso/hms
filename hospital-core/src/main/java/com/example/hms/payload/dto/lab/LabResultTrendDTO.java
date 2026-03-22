package com.example.hms.payload.dto.lab;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
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
public class LabResultTrendDTO {
    private String testName;
    private String testCode;
    private String unit;
    private String category;
    private List<LabResultTrendPointDTO> dataPoints;
}
