package com.example.hms.service.pharmacy;

import com.example.hms.model.Prescription;
import com.example.hms.payload.dto.pharmacy.CdsAlertResult;

import java.util.UUID;

/**
 * P-08: Prospective Clinical Decision Support — runs at dispense time so that
 * drug-drug interactions and overlapping active medications are surfaced
 * BEFORE the dispense is recorded, rather than retrospectively in the timeline
 * view ({@code MedicationHistoryServiceImpl}).
 */
public interface CdsCheckService {

    /**
     * Evaluate the prescription about to be dispensed against the patient's
     * active medication picture.
     *
     * @param prescription the prescription to be dispensed
     * @param patientId    the patient receiving the medication
     * @return the highest-severity alert and the messages collected; never null
     */
    CdsAlertResult checkAtDispense(Prescription prescription, UUID patientId);
}
