package com.example.hms.service.platform.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.platform.AdtIntakeProviderConfig;
import com.example.hms.payload.dto.platform.AdtIntakeProviderConfigRequestDTO;
import com.example.hms.payload.dto.platform.AdtIntakeProviderConfigResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.platform.AdtIntakeProviderConfigRepository;
import com.example.hms.service.platform.AdtIntakeProviderConfigService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdtIntakeProviderConfigServiceImpl
    implements AdtIntakeProviderConfigService {

    private static final String CONFIG_NOT_FOUND = "adt.intakeconfig.notfound";
    private static final String HOSPITAL_NOT_FOUND = "hospital.notfound";
    private static final String DEFAULT_CHIEF_COMPLAINT = "Auto-created from ADT^A01";

    private final AdtIntakeProviderConfigRepository repository;
    private final HospitalRepository hospitalRepository;

    @Override
    @Transactional
    public AdtIntakeProviderConfigResponseDTO upsert(
        AdtIntakeProviderConfigRequestDTO request, Locale locale
    ) {
        Hospital hospital = loadHospital(request.hospitalId());
        AdtIntakeProviderConfig entity = repository
            .findByHospital_Id(request.hospitalId())
            .orElseGet(AdtIntakeProviderConfig::new);
        entity.setHospital(hospital);
        applyRequestToEntity(request, entity);
        AdtIntakeProviderConfig saved = repository.save(entity);
        log.info("ADT intake-provider config upserted id={} hospital={} enabled={} A04Assignment={}",
            saved.getId(), hospital.getId(), saved.isEnabled(), saved.getDefaultAssignmentId());
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public AdtIntakeProviderConfigResponseDTO getById(UUID id, Locale locale) {
        return repository.findById(id)
            .map(this::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException(CONFIG_NOT_FOUND, id));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AdtIntakeProviderConfigResponseDTO> findByHospital(UUID hospitalId) {
        return repository.findByHospital_Id(hospitalId).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdtIntakeProviderConfigResponseDTO> findAll() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public void delete(UUID id, Locale locale) {
        AdtIntakeProviderConfig entity = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(CONFIG_NOT_FOUND, id));
        repository.delete(entity);
        log.info("ADT intake-provider config deleted id={} hospital={}",
            id, entity.getHospital() != null ? entity.getHospital().getId() : null);
    }

    private Hospital loadHospital(UUID hospitalId) {
        return hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND, hospitalId));
    }

    private void applyRequestToEntity(
        AdtIntakeProviderConfigRequestDTO request, AdtIntakeProviderConfig entity
    ) {
        entity.setAdmittingProviderId(request.admittingProviderId());
        entity.setDepartmentId(request.departmentId());
        entity.setDefaultAssignmentId(request.defaultAssignmentId());
        entity.setDefaultAdmissionType(request.defaultAdmissionType());
        entity.setDefaultAcuityLevel(request.defaultAcuityLevel());
        entity.setDefaultEncounterType(request.defaultEncounterType());
        String chiefComplaint = request.defaultChiefComplaint();
        entity.setDefaultChiefComplaint(
            chiefComplaint == null || chiefComplaint.isBlank()
                ? DEFAULT_CHIEF_COMPLAINT
                : chiefComplaint);
        // Default to disabled when the field is absent; admin must opt
        // in explicitly so we never flip auto-create on by accident on
        // first-touch.
        entity.setEnabled(Boolean.TRUE.equals(request.enabled()));
    }

    private AdtIntakeProviderConfigResponseDTO toResponse(AdtIntakeProviderConfig entity) {
        Hospital hospital = entity.getHospital();
        return new AdtIntakeProviderConfigResponseDTO(
            entity.getId(),
            hospital != null ? hospital.getId() : null,
            hospital != null ? hospital.getName() : null,
            entity.getAdmittingProviderId(),
            entity.getDepartmentId(),
            entity.getDefaultAssignmentId(),
            entity.getDefaultAdmissionType(),
            entity.getDefaultAcuityLevel(),
            entity.getDefaultEncounterType(),
            entity.getDefaultChiefComplaint(),
            entity.isEnabled(),
            entity.getCreatedAt(),
            entity.getUpdatedAt());
    }
}
