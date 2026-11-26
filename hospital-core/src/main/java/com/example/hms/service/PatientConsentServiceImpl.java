package com.example.hms.service;

import com.example.hms.mapper.HospitalMapper;
import com.example.hms.mapper.PatientConsentMapper;
import com.example.hms.mapper.PatientMapper;
import com.example.hms.model.PatientConsent;
import com.example.hms.payload.dto.HospitalResponseDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.repository.PatientConsentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read-only since E9 #65 (decision D7). See {@link PatientConsentService}.
 */
@Service
@RequiredArgsConstructor
public class PatientConsentServiceImpl implements PatientConsentService {

    private final PatientConsentRepository consentRepository;
    private final PatientConsentMapper consentMapper;
    private final PatientMapper patientMapper;
    private final HospitalMapper hospitalMapper;
    private final com.example.hms.utility.RoleValidator roleValidator;

    @Override
    @Transactional(readOnly = true)
    public Page<PatientConsentResponseDTO> getAllConsents(Pageable pageable) {
        UUID scope = roleValidator.requireActiveHospitalId();
        return mapPage(scope == null ? consentRepository.findAll(pageable)
            : consentRepository.findVisibleInHospital(scope, null, pageable), scope);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PatientConsentResponseDTO> getConsentsByPatient(UUID patientId, Pageable pageable) {
        UUID scope = roleValidator.requireActiveHospitalId();
        return mapPage(scope == null ? consentRepository.findAllByPatientId(patientId, pageable)
            : consentRepository.findVisibleInHospital(scope, patientId, pageable), scope);
    }

    private Page<PatientConsentResponseDTO> mapPage(Page<PatientConsent> page, UUID scope) {
        java.util.Map<UUID, java.util.List<PatientConsent>> byHospital = page.getContent().stream()
            .collect(java.util.stream.Collectors.groupingBy(consent -> scope != null ? scope : consent.getFromHospital().getId()));
        java.util.Map<PatientConsent, PatientResponseDTO> patients = new java.util.IdentityHashMap<>();
        byHospital.forEach((hospitalId, consents) -> {
            java.util.List<PatientResponseDTO> mapped = patientMapper.toPatientDTOs(
                consents.stream().map(PatientConsent::getPatient).toList(), hospitalId);
            for (int index = 0; index < consents.size(); index++) {
                patients.put(consents.get(index), mapped.get(index));
            }
        });
        return page.map(consent -> mapWithDetails(consent, patients.get(consent)));
    }

    private PatientConsentResponseDTO mapWithDetails(PatientConsent consent, PatientResponseDTO patientDTO) {
        HospitalResponseDTO fromHospitalDTO = hospitalMapper.toHospitalDTO(consent.getFromHospital());
        HospitalResponseDTO toHospitalDTO = hospitalMapper.toHospitalDTO(consent.getToHospital());
        return consentMapper.toDto(consent, patientDTO, fromHospitalDTO, toHospitalDTO);
    }
}
