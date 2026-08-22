package com.example.hms.service;

import com.example.hms.model.LabResult;
import com.example.hms.model.LabSpecimen;
import com.example.hms.payload.dto.InstrumentOutboxPageDTO;
import com.example.hms.payload.dto.InstrumentOutboxResponseDTO;
import com.example.hms.payload.dto.InstrumentOutboxTransportDTO;

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

    /**
     * All outbox messages for a lab order, whatever their status.
     *
     * <p>Until 2026-08-22 this returned PENDING rows only — which meant the one
     * monitoring read that existed silently dropped the ERROR rows an operator
     * would be hunting for.
     */
    List<InstrumentOutboxResponseDTO> getMessagesByLabOrder(UUID labOrderId);

    /** One page of the hospital-scoped outbox queue, payload elided, with status counts. */
    InstrumentOutboxPageDTO search(String status, int page, int size);

    /** One message including its full HL7 payload. Scoped 404 outside the caller's hospital. */
    InstrumentOutboxResponseDTO getMessage(UUID id);

    /**
     * Put an ERROR row back in the dispatch queue.
     *
     * <p>ERROR is otherwise an absorbing state: the sweep only selects PENDING,
     * so once a message is parked nothing in the system can ever move it again —
     * even after the analyser comes back.
     */
    InstrumentOutboxResponseDTO retry(UUID id);

    /** Read-only view of the outbound MLLP transport configuration. */
    InstrumentOutboxTransportDTO getTransportStatus();
}
