package com.example.hms.service;

import com.example.hms.payload.dto.LabQcEventRequestDTO;
import com.example.hms.payload.dto.LabQcEventResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Locale;
import java.util.UUID;

public interface LabQcEventService {

    /** Record a new QC event for an analyzer run. */
    LabQcEventResponseDTO recordQcEvent(LabQcEventRequestDTO request, Locale locale);

    /** Retrieve a single QC event by ID. */
    LabQcEventResponseDTO getQcEventById(UUID id, Locale locale);

    /** Paginated list of QC events scoped to the caller's hospital. */
    Page<LabQcEventResponseDTO> getQcEventsByHospital(UUID hospitalId, Pageable pageable, Locale locale);

    /** Paginated list of QC events for a specific test definition (for Levey-Jennings charts). */
    Page<LabQcEventResponseDTO> getQcEventsByDefinition(UUID testDefinitionId, Pageable pageable, Locale locale);
}
