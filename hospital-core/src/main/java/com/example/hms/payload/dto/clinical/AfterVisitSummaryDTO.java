package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * After-Visit Summary (AVS) returned after a successful check-out (MVP 6).
 * Contains a comprehensive summary of the visit for patient review.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AfterVisitSummaryDTO {

    private UUID encounterId;
    private UUID appointmentId;

    /* --- Visit info --- */
    private LocalDateTime visitDate;
    private String providerName;
    private String departmentName;
    private String hospitalName;

    /* --- Patient --- */
    private UUID patientId;
    private String patientName;

    /* --- Clinical summary --- */
    private String chiefComplaint;
    private List<String> dischargeDiagnoses;
    private String prescriptionSummary;
    private String referralSummary;
    private String followUpInstructions;
    private String patientEducationMaterials;

    /* --- Status --- */
    private String encounterStatus;
    private String appointmentStatus;
    private LocalDateTime checkoutTimestamp;

    /* --- Follow-up --- */
    private UUID followUpAppointmentId;
    private String followUpAppointmentDate;
}
