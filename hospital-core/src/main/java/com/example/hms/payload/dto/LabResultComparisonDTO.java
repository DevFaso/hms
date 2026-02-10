package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * DTO for comparing sequential lab results to identify trends and significant changes
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabResultComparisonDTO {

    private String testCode;
    private String testName;
    private String patientId;
    private String patientName;
    
    // Current/latest result
    private LabResultTrendPointDTO currentResult;
    
    // Previous result for comparison
    private LabResultTrendPointDTO previousResult;
    
    // Comparison metadata
    private ComparisonMetadata comparison;
    
    // Complete trend history for graphing
    private List<LabResultTrendPointDTO> trendHistory;
    
    // Reference ranges for context
    private List<LabResultReferenceRangeDTO> referenceRanges;
    
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ComparisonMetadata {
        // Absolute change
        private String absoluteChange;
        
        // Percentage change (if numeric values)
        private Double percentageChange;
        
        // Trend direction
        private TrendDirection trendDirection;
        
        // Time difference between measurements
        private Long daysBetween;
        
        // Clinical significance
        private String significanceLevel; // CRITICAL, SIGNIFICANT, MINOR, STABLE
        
        // Whether change crosses reference range boundaries
        private Boolean crossedThreshold;
        
        // Human-readable interpretation
        private String interpretation;
    }
    
    public enum TrendDirection {
        INCREASING,
        DECREASING,
        STABLE,
        FLUCTUATING,
        INSUFFICIENT_DATA
    }
}
