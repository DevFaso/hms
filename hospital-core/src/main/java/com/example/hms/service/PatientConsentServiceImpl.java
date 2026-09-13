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

    @Override
    @Transactional(readOnly = true)
    public Page<PatientConsentResponseDTO> getAllConsents(Pageable pageable) {
        return consentRepository.findAll(pageable).map(this::mapWithDetails);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PatientConsentResponseDTO> getConsentsByPatient(UUID patientId, Pageable pageable) {
        return consentRepository.findAllByPatientId(patientId, pageable).map(this::mapWithDetails);
    }

    private PatientConsentResponseDTO mapWithDetails(PatientConsent consent) {
        PatientResponseDTO patientDTO = patientMapper.toPatientDTO(
            consent.getPatient(),
            consent.getFromHospital().getId(),
            true,
            true
        );
        HospitalResponseDTO fromHospitalDTO = hospitalMapper.toHospitalDTO(consent.getFromHospital());
        HospitalResponseDTO toHospitalDTO = hospitalMapper.toHospitalDTO(consent.getToHospital());
        return consentMapper.toDto(consent, patientDTO, fromHospitalDTO, toHospitalDTO);
    }
}
