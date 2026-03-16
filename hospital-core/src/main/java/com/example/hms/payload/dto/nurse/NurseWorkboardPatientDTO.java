package com.example.hms.payload.dto.nurse;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Patient row returned by GET /nurse/workboard.
 * Surfaces the key contextual details a nurse needs at a glance for each
 * patient currently in their care.
 */
@Getter
@Builder
public class NurseWorkboardPatientDTO {

    private UUID patientId;
    private String patientName;
    private String mrn;
    private String roomBed;

    /** AcuityLevel enum name (e.g. "LEVEL_3_MAJOR"). */
    private String acuityLevel;

    private UUID admissionId;
    private String departmentName;
    private String attendingDoctor;

    private LocalDateTime admittedAt;
    private LocalDateTime lastVitalsTime;
    private boolean vitalsDue;
    private long medsDue;
}
