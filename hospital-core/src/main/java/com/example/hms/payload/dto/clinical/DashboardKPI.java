package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for Dashboard KPI tile
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardKPI {

    private String key;

    private String label;

    private Integer value;

    private String unit;

    private Double deltaNum;

    private String trend; // up, down, stable
}
