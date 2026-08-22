package com.example.hms.service;

import com.example.hms.payload.dto.IntakeOutputEntryRequestDTO;
import com.example.hms.payload.dto.IntakeOutputSummaryDTO;

import java.time.LocalDateTime;
import java.util.UUID;

public interface IntakeOutputService {

    /**
     * Record one fluid intake/output event. Writes always require a hospital
     * scope and a patient actively registered there.
     */
    IntakeOutputSummaryDTO.Entry record(UUID patientId,
                                        UUID hospitalId,
                                        UUID recorderUserId,
                                        IntakeOutputEntryRequestDTO request);

    /**
     * Entries plus server-computed totals for a window. Null from/to default
     * to the last 24 hours. Null hospitalId = unscoped (super-admin) read.
     */
    IntakeOutputSummaryDTO getSummary(UUID patientId,
                                      UUID hospitalId,
                                      LocalDateTime from,
                                      LocalDateTime to);
}
