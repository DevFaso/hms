package com.example.hms.service;

import com.example.hms.payload.dto.mortality.DeathRecordAmendmentDTO;
import com.example.hms.payload.dto.mortality.DeathRecordRequestDTO;
import com.example.hms.payload.dto.mortality.DeathRecordResponseDTO;
import com.example.hms.payload.dto.mortality.MortalityRegisterDTO;
import com.example.hms.payload.dto.mortality.RecordDeathResponseDTO;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Death and mortality (Tier 2 item 29).
 *
 * <p>Before this, the system had no concept of a patient dying. A patient who
 * died stayed ACTIVE with open admissions, live appointments, and — worst —
 * reminder and recall sweeps that select purely on appointment state, so they
 * would text the family of someone who died last week about a follow-up.
 *
 * <p>Recording a death therefore does two things at once, and both matter: it
 * writes the certificate, and it CLOSES the record. The closure is reported
 * back to the caller rather than done silently, because a clerk is entitled to
 * know what their keystroke just cancelled.
 */
public interface MortalityService {

    /**
     * Record a death: write the certificate, stamp {@code Patient.deceasedAt},
     * and close what is still open — admissions, encounters, future
     * appointments and pending recalls.
     *
     * <p>Refuses a second death record for the same patient. There is
     * deliberately no un-death path: reversing a death is a data-correction
     * exercise, not something a form should be able to trigger.
     */
    RecordDeathResponseDTO recordDeath(DeathRecordRequestDTO request);

    /**
     * Revise the cause of death — what an autopsy or coroner routinely changes
     * weeks later. Cannot touch the time of death or the patient: the account
     * is amendable, the fact is not.
     */
    DeathRecordResponseDTO amendDeathRecord(UUID recordId, DeathRecordAmendmentDTO request);

    DeathRecordResponseDTO getForPatient(UUID patientId);

    /**
     * The register for a period, with maternal (WHO definition), late maternal,
     * perinatal and stillbirth counts broken out — the numbers the facility is
     * measured on and the ones the DHIS2 export needs.
     */
    MortalityRegisterDTO getRegister(LocalDate from, LocalDate to);
}
