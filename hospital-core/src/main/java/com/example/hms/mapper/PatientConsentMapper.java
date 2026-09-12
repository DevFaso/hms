package com.example.hms.mapper;

import com.example.hms.model.PatientConsent;
import com.example.hms.payload.dto.HospitalResponseDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Entity to response only, since E9 #65: nothing builds a {@link PatientConsent}
 * from a request any more.
 */
@Component
@RequiredArgsConstructor
public class PatientConsentMapper {

    private final PatientMapper patientMapper;
    private final HospitalMapper hospitalMapper;

    /**
     * Converts a PatientConsent entity to PatientConsentResponseDTO including full patient and hospital info.
     */
    public PatientConsentResponseDTO toDto(PatientConsent consent) {
        return PatientConsentResponseDTO.builder()
                .id(consent.getId())
                .consentGiven(consent.isConsentGiven())
                .consentTimestamp(consent.getCreatedAt())
                .consentExpiration(consent.getConsentExpiration())
                .purpose(consent.getPurpose())
                .consentType(consent.getConsentType())
                .scope(consent.getScope())
                .patientId(consent.getPatient().getId())
                .patient(patientMapper.toPatientDTO(consent.getPatient(), consent.getFromHospital().getId()))
                .fromHospital(hospitalMapper.toHospitalDTO(consent.getFromHospital()))
                .toHospital(hospitalMapper.toHospitalDTO(consent.getToHospital()))
                .build();
    }

    /**
     * Converts a PatientConsent entity to PatientConsentResponseDTO using externally prepared DTOs.
     */
    public PatientConsentResponseDTO toDto(PatientConsent consent,
                                           PatientResponseDTO patientDTO,
                                           HospitalResponseDTO fromHospitalDTO,
                                           HospitalResponseDTO toHospitalDTO) {
        return PatientConsentResponseDTO.builder()
                .id(consent.getId())
                .consentGiven(consent.isConsentGiven())
                .consentTimestamp(consent.getCreatedAt())
                .consentExpiration(consent.getConsentExpiration())
                .purpose(consent.getPurpose())
                .consentType(consent.getConsentType())
                .scope(consent.getScope())
                .patientId(patientDTO != null ? patientDTO.getId() : null)
                .patient(patientDTO)
                .fromHospital(fromHospitalDTO)
                .toHospital(toHospitalDTO)
                .build();
    }
}
