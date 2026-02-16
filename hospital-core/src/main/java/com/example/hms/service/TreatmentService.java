package com.example.hms.service;

import com.example.hms.payload.dto.TreatmentRequestDTO;
import com.example.hms.payload.dto.TreatmentResponseDTO;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface TreatmentService {
    @Transactional
    TreatmentResponseDTO createTreatment(TreatmentRequestDTO dto, Locale locale, String effectiveRoleHeader);

    TreatmentResponseDTO updateTreatment(UUID id, TreatmentRequestDTO dto, Locale locale);
    void deleteTreatment(UUID id);
    List<TreatmentResponseDTO> getAllTreatments(Locale locale, String language);
    TreatmentResponseDTO getTreatmentById(UUID id, Locale locale, String language);
}

