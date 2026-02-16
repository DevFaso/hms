package com.example.hms.service;


import com.example.hms.payload.dto.ServiceTranslationRequestDTO;
import com.example.hms.payload.dto.ServiceTranslationResponseDTO;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface ServiceTranslationService {

    ServiceTranslationResponseDTO createTranslation(ServiceTranslationRequestDTO dto, Locale locale);

    ServiceTranslationResponseDTO getTranslationById(UUID id, Locale locale);

    List<ServiceTranslationResponseDTO> getAllTranslations(Locale locale);

    ServiceTranslationResponseDTO updateTranslation(UUID id, ServiceTranslationRequestDTO dto, Locale locale);

    void deleteTranslation(UUID id, Locale locale);
}
