package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.PharmacyFill;
import com.example.hms.payload.dto.medication.PharmacyFillRequestDTO;
import com.example.hms.payload.dto.medication.PharmacyFillResponseDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Mapper for PharmacyFill entity and DTOs.
 */
@Component
public class PharmacyFillMapper {

    /**
     * Convert entity to response DTO.
     */
    public PharmacyFillResponseDTO toResponseDTO(PharmacyFill entity) {
        if (entity == null) {
            return null;
        }

        Patient patient = entity.getPatient();
        Hospital hospital = entity.getHospital();
        Prescription prescription = entity.getPrescription();

        // Calculate expected depletion date
        LocalDate expectedDepletion = null;
        if (entity.getFillDate() != null && entity.getDaysSupply() != null) {
            expectedDepletion = entity.getFillDate().plusDays(entity.getDaysSupply());
        }

        return PharmacyFillResponseDTO.builder()
            .id(entity.getId())
            .patientId(patient != null ? patient.getId() : null)
            .patientName(patient != null ? buildPatientName(patient) : null)
            .patientMrn(patient != null && hospital != null ? resolvePatientMrn(patient, hospital) : null)
            .hospitalId(hospital != null ? hospital.getId() : null)
            .hospitalName(hospital != null ? hospital.getName() : null)
            .prescriptionId(prescription != null ? prescription.getId() : null)
            .medicationName(entity.getMedicationName())
            .ndcCode(entity.getNdcCode())
            .rxnormCode(entity.getRxnormCode())
            .strength(entity.getStrength())
            .dosageForm(entity.getDosageForm())
            .fillDate(entity.getFillDate())
            .quantityDispensed(entity.getQuantityDispensed())
            .quantityUnit(entity.getQuantityUnit())
            .daysSupply(entity.getDaysSupply())
            .refillNumber(entity.getRefillNumber())
            .directions(entity.getDirections())
            .expectedDepletionDate(expectedDepletion)
            .pharmacyName(entity.getPharmacyName())
            .pharmacyNpi(entity.getPharmacyNpi())
            .pharmacyNcpdp(entity.getPharmacyNcpdp())
            .pharmacyPhone(entity.getPharmacyPhone())
            .pharmacyAddress(entity.getPharmacyAddress())
            .prescriberName(entity.getPrescriberName())
            .prescriberNpi(entity.getPrescriberNpi())
            .prescriberDea(entity.getPrescriberDea())
            .sourceSystem(entity.getSourceSystem())
            .externalReferenceId(entity.getExternalReferenceId())
            .controlledSubstance(entity.isControlledSubstance())
            .genericSubstitution(entity.isGenericSubstitution())
            .notes(entity.getNotes())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    /**
     * Convert request DTO to entity (for creation).
     */
    public PharmacyFill toEntity(PharmacyFillRequestDTO dto,
                                  Patient patient,
                                  Hospital hospital,
                                  Prescription prescription) {
        if (dto == null) {
            return null;
        }

        return PharmacyFill.builder()
            .patient(patient)
            .hospital(hospital)
            .prescription(prescription)
            .medicationName(dto.getMedicationName())
            .ndcCode(dto.getNdcCode())
            .rxnormCode(dto.getRxnormCode())
            .strength(dto.getStrength())
            .dosageForm(dto.getDosageForm())
            .fillDate(dto.getFillDate())
            .quantityDispensed(dto.getQuantityDispensed())
            .quantityUnit(dto.getQuantityUnit())
            .daysSupply(dto.getDaysSupply())
            .refillNumber(dto.getRefillNumber())
            .directions(dto.getDirections())
            .pharmacyName(dto.getPharmacyName())
            .pharmacyNpi(dto.getPharmacyNpi())
            .pharmacyNcpdp(dto.getPharmacyNcpdp())
            .pharmacyPhone(dto.getPharmacyPhone())
            .pharmacyAddress(dto.getPharmacyAddress())
            .prescriberName(dto.getPrescriberName())
            .prescriberNpi(dto.getPrescriberNpi())
            .prescriberDea(dto.getPrescriberDea())
            .sourceSystem(dto.getSourceSystem())
            .externalReferenceId(dto.getExternalReferenceId())
            .controlledSubstance(dto.getControlledSubstance() != null && dto.getControlledSubstance())
            .genericSubstitution(dto.getGenericSubstitution() != null && dto.getGenericSubstitution())
            .notes(dto.getNotes())
            .build();
    }

    /**
     * Update existing entity with request DTO data.
     */
    public void updateEntity(PharmacyFill entity, PharmacyFillRequestDTO dto) {
        if (entity == null || dto == null) {
            return;
        }

        entity.setMedicationName(dto.getMedicationName());
        entity.setNdcCode(dto.getNdcCode());
        entity.setRxnormCode(dto.getRxnormCode());
        entity.setStrength(dto.getStrength());
        entity.setDosageForm(dto.getDosageForm());
        entity.setFillDate(dto.getFillDate());
        entity.setQuantityDispensed(dto.getQuantityDispensed());
        entity.setQuantityUnit(dto.getQuantityUnit());
        entity.setDaysSupply(dto.getDaysSupply());
        entity.setRefillNumber(dto.getRefillNumber());
        entity.setDirections(dto.getDirections());
        entity.setPharmacyName(dto.getPharmacyName());
        entity.setPharmacyNpi(dto.getPharmacyNpi());
        entity.setPharmacyNcpdp(dto.getPharmacyNcpdp());
        entity.setPharmacyPhone(dto.getPharmacyPhone());
        entity.setPharmacyAddress(dto.getPharmacyAddress());
        entity.setPrescriberName(dto.getPrescriberName());
        entity.setPrescriberNpi(dto.getPrescriberNpi());
        entity.setPrescriberDea(dto.getPrescriberDea());
        entity.setSourceSystem(dto.getSourceSystem());
        entity.setExternalReferenceId(dto.getExternalReferenceId());
        entity.setControlledSubstance(dto.getControlledSubstance() != null && dto.getControlledSubstance());
        entity.setGenericSubstitution(dto.getGenericSubstitution() != null && dto.getGenericSubstitution());
        entity.setNotes(dto.getNotes());
    }

    // Helper methods

    private String buildPatientName(Patient patient) {
        String firstName = patient.getFirstName() != null ? patient.getFirstName() : "";
        String lastName = patient.getLastName() != null ? patient.getLastName() : "";
        return (firstName + " " + lastName).trim();
    }

    private String resolvePatientMrn(Patient patient, Hospital hospital) {
        if (patient == null || hospital == null) {
            return null;
        }
        String mrn = patient.getMrnForHospital(hospital.getId());
        return mrn != null ? mrn : patient.getId().toString();
    }
}
