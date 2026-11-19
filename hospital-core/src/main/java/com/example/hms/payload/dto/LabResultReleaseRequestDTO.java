package com.example.hms.payload.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * r63: what a release may have to document, sent as the optional body of
 * {@code POST /lab-results/{id}/release}.
 *
 * <ul>
 *   <li>D2305: for a CRITICAL result, who was told, when, and the value they
 *       read back. Required for a critical result; ignored otherwise.</li>
 *   <li>D2304: the reason for releasing while the test's latest QC run is
 *       rejected. Required only then.</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabResultReleaseRequestDTO {

    @Size(max = 255)
    private String criticalCommunicatedTo;

    private LocalDateTime criticalCommunicatedAt;

    @Size(max = 255)
    private String criticalReadBack;

    @Size(max = 2048)
    private String qcOverrideReason;
}
