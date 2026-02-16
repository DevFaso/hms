package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTO for Roomed Patient in Doctor Dashboard
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomedPatientDTO {

    private UUID id;

    private UUID encounterId;

    private String patientName;

    private Integer age;

    private String sex;

    private String mrn;

    private String room;

    private String triageStatus; // NOT_TRIAGED, TRIAGE_IN_PROGRESS, TRIAGED, READY_FOR_PROVIDER

    private String chiefComplaint;

    private Integer waitTimeMinutes;

    private LocalDateTime arrivalTime;

    private Map<String, Object> vitals; // temperature, bloodPressure, heartRate, etc.

    private List<String> flags;

    private PrepStatusDTO prepStatus;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PrepStatusDTO {
        private Boolean labsDrawn;
        private Boolean imagingOrdered;
        private Boolean consentSigned;
    }
}
