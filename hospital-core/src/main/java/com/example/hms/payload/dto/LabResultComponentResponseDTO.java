package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * r63 (NFS panels): one component value of a panel result, graded against
 * its own component's reference ranges by the same rules as a single value.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabResultComponentResponseDTO {
    private String code;
    private String name;
    private String value;
    private String unit;
    /** NORMAL, LOW, HIGH, CRITICAL or UNSPECIFIED, as for a single value. */
    private String severityFlag;
    /** The range this value was graded against; null when none applies. */
    private LabResultReferenceRangeDTO referenceRange;
}
