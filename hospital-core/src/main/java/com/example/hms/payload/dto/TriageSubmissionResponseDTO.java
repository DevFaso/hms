package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response returned after a successful triage submission (MVP 2).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TriageSubmissionResponseDTO {

    private UUID encounterId;
    private String encounterStatus;
    private Integer esiScore;
    private String urgency;
    private String roomAssignment;
    private LocalDateTime triageTimestamp;
    private LocalDateTime roomedTimestamp;
    private String chiefComplaint;

    /** ID of the created PatientVitalSign record. */
    private UUID vitalSignId;

    /** QA D1564: the newborn warnings on the triage set; empty for anyone 28 days old or more. */
    private java.util.List<NewbornVitalAlertDTO> newbornAlerts;

    /** QA D1890, r54 (provisional): the obstetric severe-blood-pressure warning (pregnancy, or 42 days after a birth); empty when none. */
    private java.util.List<NewbornVitalAlertDTO> postpartumAlerts;

    /** r54 (provisional): the adult severe-blood-pressure warning (WARNING, or URGENT with a sign of organ damage); empty when none. */
    private java.util.List<NewbornVitalAlertDTO> hypertensionAlerts;

    /**
     * Policy 2026-11-20 (provisional): on a write only, the maternal early warning of a pregnant or
     * postpartum woman's set (the level of concern when a review is due, and each shock-index alert);
     * empty when none.
     */
    private java.util.List<NewbornVitalAlertDTO> maternalAlerts;
}
