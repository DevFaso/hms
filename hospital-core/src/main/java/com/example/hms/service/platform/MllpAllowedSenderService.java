package com.example.hms.service.platform;

import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.platform.MllpAllowedSenderRequestDTO;
import com.example.hms.payload.dto.platform.MllpAllowedSenderResponseDTO;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public interface MllpAllowedSenderService {

    /**
     * Resolve the receiving Hospital for an inbound HL7 v2 sender pair
     * (MSH-3 sending application, MSH-4 sending facility). Returns empty
     * if no active allowlist entry matches — the dispatcher must reject
     * such messages with AR.
     */
    Optional<Hospital> resolveHospital(String sendingApplication, String sendingFacility);

    MllpAllowedSenderResponseDTO create(MllpAllowedSenderRequestDTO request, Locale locale);

    MllpAllowedSenderResponseDTO update(UUID id, MllpAllowedSenderRequestDTO request, Locale locale);

    MllpAllowedSenderResponseDTO getById(UUID id, Locale locale);

    List<MllpAllowedSenderResponseDTO> findAll(Locale locale);

    List<MllpAllowedSenderResponseDTO> findByHospital(UUID hospitalId, Locale locale);

    void deactivate(UUID id, Locale locale);
}
