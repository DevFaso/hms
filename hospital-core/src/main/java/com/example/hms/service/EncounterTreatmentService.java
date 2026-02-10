package com.example.hms.service;

import com.example.hms.payload.dto.EncounterTreatmentRequestDTO;
import com.example.hms.payload.dto.EncounterTreatmentResponseDTO;

import java.util.List;
import java.util.UUID;

public interface EncounterTreatmentService {
    EncounterTreatmentResponseDTO addTreatmentToEncounter(EncounterTreatmentRequestDTO dto);
    List<EncounterTreatmentResponseDTO> getTreatmentsByEncounter(UUID encounterId);

}
