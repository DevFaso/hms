package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ServiceTranslationMapper;
import com.example.hms.model.ServiceTranslation;
import com.example.hms.model.Treatment;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.ServiceTranslationRequestDTO;
import com.example.hms.payload.dto.ServiceTranslationResponseDTO;
import com.example.hms.repository.ServiceTranslationRepository;
import com.example.hms.repository.TreatmentRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ServiceTranslationServiceImpl implements ServiceTranslationService {
    private static final String TRANSLATION_NOT_FOUND_KEY = "translation.not.found";


    private final ServiceTranslationRepository translationRepository;
    private final TreatmentRepository treatmentRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final ServiceTranslationMapper mapper;
    private final MessageSource messageSource;

    @Override
    @Transactional
    public ServiceTranslationResponseDTO createTranslation(ServiceTranslationRequestDTO dto, Locale locale) {
        Treatment treatment = treatmentRepository.findById(dto.getTreatmentId())
                .orElseThrow(() -> new ResourceNotFoundException(getMessage("treatment.not.found", locale)));

        UserRoleHospitalAssignment assignment = assignmentRepository.findById(dto.getAssignmentId())
                .orElseThrow(() -> new ResourceNotFoundException(getMessage("assignment.not.found", locale)));

        ServiceTranslation translation = mapper.toEntity(dto, treatment, assignment);
        ServiceTranslation saved = translationRepository.save(translation);
        return mapper.toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ServiceTranslationResponseDTO getTranslationById(UUID id, Locale locale) {
        ServiceTranslation translation = translationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(getMessage(TRANSLATION_NOT_FOUND_KEY, locale)));
        return mapper.toDto(translation);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServiceTranslationResponseDTO> getAllTranslations(Locale locale) {
        return translationRepository.findAll()
                .stream()
                .map(mapper::toDto)
                .toList();
    }

    @Override
    @Transactional
    public ServiceTranslationResponseDTO updateTranslation(UUID id, ServiceTranslationRequestDTO dto, Locale locale) {
        ServiceTranslation translation = translationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(getMessage(TRANSLATION_NOT_FOUND_KEY, locale)));

        mapper.updateEntity(translation, dto);
        ServiceTranslation updated = translationRepository.save(translation);
        return mapper.toDto(updated);
    }

    @Override
    @Transactional
    public void deleteTranslation(UUID id, Locale locale) {
        if (!translationRepository.existsById(id)) {
            throw new ResourceNotFoundException(getMessage(TRANSLATION_NOT_FOUND_KEY, locale));
        }
        translationRepository.deleteById(id);
    }

    private String getMessage(String key, Locale locale) {
        return messageSource.getMessage(key, null, locale);
    }
}

