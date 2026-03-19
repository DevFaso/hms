package com.example.hms.service;

import com.example.hms.model.LabResult;
import com.example.hms.model.LabSpecimen;
import com.example.hms.payload.dto.InstrumentOutboxResponseDTO;

import java.util.List;
import java.util.UUID;

public interface InstrumentOutboxService {

    /**
     * Enqueue an OML^O21 outbound message when a specimen is received at the lab.
     * Fires on specimen receipt to notify the connected analyzer.
     */
    void enqueueSpecimenReceived(LabSpecimen specimen);

    /**
     * Enqueue an ORU^R01 outbound message when a lab result is recorded.
     * Used for downstream result distribution (e.g. EMR notification).
     */
    void enqueueResultObservation(LabResult result);

    /** Return pending messages for a given lab order (for monitoring/retry). */
    List<InstrumentOutboxResponseDTO> getPendingMessagesByLabOrder(UUID labOrderId);
}
