package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientConsent;
import com.example.hms.payload.dto.HospitalResponseDTO;
import com.example.hms.payload.dto.PatientConsentRequestDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PatientConsentMapper {

    private final PatientMapper patientMapper;
    private final HospitalMapper hospitalMapper;

    /**
     * Converts a PatientConsentRequestDTO to a PatientConsent entity.
     */
    public PatientConsent toEntity(PatientConsentRequestDTO dto, Patient patient, Hospital fromHospital, Hospital toHospital) {
        return PatientConsent.builder()
                .patient(patient)
                .fromHospital(fromHospital)
                .toHospital(toHospital)
                .consentGiven(true)
                .consentExpiration(dto.getConsentExpiration())
                .purpose(dto.getPurpose())
                .build();
    }

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
                .patientId(patientDTO != null ? patientDTO.getId() : null)
                .patient(patientDTO)
                .fromHospital(fromHospitalDTO)
                .toHospital(toHospitalDTO)
                .build();
    }
}
