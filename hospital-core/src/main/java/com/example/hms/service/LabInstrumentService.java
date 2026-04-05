package com.example.hms.service;

import com.example.hms.payload.dto.LabInstrumentRequestDTO;
import com.example.hms.payload.dto.LabInstrumentResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Locale;
import java.util.UUID;

/**
 * Manages lab instruments – registration, calibration/maintenance tracking.
 */
public interface LabInstrumentService {

    Page<LabInstrumentResponseDTO> getByHospital(UUID hospitalId, Pageable pageable, Locale locale);

    LabInstrumentResponseDTO getById(UUID id, Locale locale);

    LabInstrumentResponseDTO create(UUID hospitalId, LabInstrumentRequestDTO dto, Locale locale);

    LabInstrumentResponseDTO update(UUID id, LabInstrumentRequestDTO dto, Locale locale);

    void deactivate(UUID id, Locale locale);
}
