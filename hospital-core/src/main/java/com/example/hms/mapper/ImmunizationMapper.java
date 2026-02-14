package com.example.hms.mapper;

import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientImmunization;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.medicalhistory.ImmunizationRequestDTO;
import com.example.hms.payload.dto.medicalhistory.ImmunizationResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class ImmunizationMapper {

    public ImmunizationResponseDTO toResponseDTO(PatientImmunization entity) {
        if (entity == null) {
            return null;
        }

        return ImmunizationResponseDTO.builder()
                .id(entity.getId())
                .patientId(entity.getPatient() != null ? entity.getPatient().getId() : null)
                .patientName(entity.getPatient() != null ? entity.getPatient().getFullName() : null)
                .hospitalId(entity.getHospital() != null ? entity.getHospital().getId() : null)
                .hospitalName(entity.getHospital() != null ? entity.getHospital().getName() : null)
                .administeredByStaffId(entity.getAdministeredBy() != null ? entity.getAdministeredBy().getId() : null)
                .administeredByName(entity.getAdministeredBy() != null ? entity.getAdministeredBy().getFullName() : null)
                .encounterId(entity.getEncounter() != null ? entity.getEncounter().getId() : null)
                // Vaccine
                .vaccineCode(entity.getVaccineCode())
                .vaccineDisplay(entity.getVaccineDisplay())
                .vaccineType(entity.getVaccineType())
                .targetDisease(entity.getTargetDisease())
                // Administration
                .administrationDate(entity.getAdministrationDate())
                .doseNumber(entity.getDoseNumber())
                .totalDosesInSeries(entity.getTotalDosesInSeries())
                .doseQuantity(entity.getDoseQuantity())
                .doseUnit(entity.getDoseUnit())
                .route(entity.getRoute())
                .site(entity.getSite())
                // Product
                .manufacturer(entity.getManufacturer())
                .lotNumber(entity.getLotNumber())
                .expirationDate(entity.getExpirationDate())
                .ndcCode(entity.getNdcCode())
                // Status
                .status(entity.getStatus())
                .statusReason(entity.getStatusReason())
                .verified(entity.getVerified())
                .sourceOfRecord(entity.getSourceOfRecord())
                // Reaction
                .adverseReaction(entity.getAdverseReaction())
                .reactionDescription(entity.getReactionDescription())
                .reactionSeverity(entity.getReactionSeverity())
                .contraindication(entity.getContraindication())
                .contraindicationReason(entity.getContraindicationReason())
                // Scheduling
                .nextDoseDueDate(entity.getNextDoseDueDate())
                .reminderSent(entity.getReminderSent())
                .reminderSentDate(entity.getReminderSentDate())
                .overdue(entity.getOverdue())
                // Clinical Significance
                .requiredForSchool(entity.getRequiredForSchool())
                .requiredForTravel(entity.getRequiredForTravel())
                .occupationalRequirement(entity.getOccupationalRequirement())
                .pregnancyRelated(entity.getPregnancyRelated())
                // Documentation
                .visGiven(entity.getVisGiven())
                .visDate(entity.getVisDate())
                .consentObtained(entity.getConsentObtained())
                .consentDate(entity.getConsentDate())
                .insuranceReported(entity.getInsuranceReported())
                .registryReported(entity.getRegistryReported())
                .registryReportedDate(entity.getRegistryReportedDate())
                .notes(entity.getNotes())
                .active(entity.getActive())
                // Audit
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public PatientImmunization toEntity(ImmunizationRequestDTO dto, Patient patient,
                                        Hospital hospital, Staff administeredBy, Encounter encounter) {
        if (dto == null) {
            return null;
        }

        return PatientImmunization.builder()
                .patient(patient)
                .hospital(hospital)
                .administeredBy(administeredBy)
                .encounter(encounter)
                // Vaccine
                .vaccineCode(dto.getVaccineCode())
                .vaccineDisplay(dto.getVaccineDisplay())
                .vaccineType(dto.getVaccineType())
                .targetDisease(dto.getTargetDisease())
                // Administration
                .administrationDate(dto.getAdministrationDate())
                .doseNumber(dto.getDoseNumber())
                .totalDosesInSeries(dto.getTotalDosesInSeries())
                .doseQuantity(dto.getDoseQuantity())
                .doseUnit(dto.getDoseUnit())
                .route(dto.getRoute())
                .site(dto.getSite())
                // Product
                .manufacturer(dto.getManufacturer())
                .lotNumber(dto.getLotNumber())
                .expirationDate(dto.getExpirationDate())
                .ndcCode(dto.getNdcCode())
                // Status
                .status(dto.getStatus())
                .statusReason(dto.getStatusReason())
                .verified(dto.getVerified())
                .sourceOfRecord(dto.getSourceOfRecord())
                // Reaction
                .adverseReaction(dto.getAdverseReaction())
                .reactionDescription(dto.getReactionDescription())
                .reactionSeverity(dto.getReactionSeverity())
                .contraindication(dto.getContraindication())
                .contraindicationReason(dto.getContraindicationReason())
                // Scheduling
                .nextDoseDueDate(dto.getNextDoseDueDate())
                .reminderSent(dto.getReminderSent())
                .reminderSentDate(dto.getReminderSentDate())
                // Clinical Significance
                .requiredForSchool(dto.getRequiredForSchool())
                .requiredForTravel(dto.getRequiredForTravel())
                .occupationalRequirement(dto.getOccupationalRequirement())
                .pregnancyRelated(dto.getPregnancyRelated())
                // Documentation
                .visGiven(dto.getVisGiven())
                .visDate(dto.getVisDate())
                .consentObtained(dto.getConsentObtained())
                .consentDate(dto.getConsentDate())
                .insuranceReported(dto.getInsuranceReported())
                .registryReported(dto.getRegistryReported())
                .registryReportedDate(dto.getRegistryReportedDate())
                .notes(dto.getNotes())
                .active(dto.getActive() == null || dto.getActive())
                .build();
    }

    public void updateEntity(PatientImmunization entity, ImmunizationRequestDTO dto) {
        if (entity == null || dto == null) {
            return;
        }

        // Vaccine
        entity.setVaccineCode(dto.getVaccineCode());
        entity.setVaccineDisplay(dto.getVaccineDisplay());
        entity.setVaccineType(dto.getVaccineType());
        entity.setTargetDisease(dto.getTargetDisease());
        // Administration
        entity.setAdministrationDate(dto.getAdministrationDate());
        entity.setDoseNumber(dto.getDoseNumber());
        entity.setTotalDosesInSeries(dto.getTotalDosesInSeries());
        entity.setDoseQuantity(dto.getDoseQuantity());
        entity.setDoseUnit(dto.getDoseUnit());
        entity.setRoute(dto.getRoute());
        entity.setSite(dto.getSite());
        // Product
        entity.setManufacturer(dto.getManufacturer());
        entity.setLotNumber(dto.getLotNumber());
        entity.setExpirationDate(dto.getExpirationDate());
        entity.setNdcCode(dto.getNdcCode());
        // Status
        entity.setStatus(dto.getStatus());
        entity.setStatusReason(dto.getStatusReason());
        entity.setVerified(dto.getVerified());
        entity.setSourceOfRecord(dto.getSourceOfRecord());
        // Reaction
        entity.setAdverseReaction(dto.getAdverseReaction());
        entity.setReactionDescription(dto.getReactionDescription());
        entity.setReactionSeverity(dto.getReactionSeverity());
        entity.setContraindication(dto.getContraindication());
        entity.setContraindicationReason(dto.getContraindicationReason());
        // Scheduling
        entity.setNextDoseDueDate(dto.getNextDoseDueDate());
        entity.setReminderSent(dto.getReminderSent());
        entity.setReminderSentDate(dto.getReminderSentDate());
        // Clinical Significance
        entity.setRequiredForSchool(dto.getRequiredForSchool());
        entity.setRequiredForTravel(dto.getRequiredForTravel());
        entity.setOccupationalRequirement(dto.getOccupationalRequirement());
        entity.setPregnancyRelated(dto.getPregnancyRelated());
        // Documentation
        entity.setVisGiven(dto.getVisGiven());
        entity.setVisDate(dto.getVisDate());
        entity.setConsentObtained(dto.getConsentObtained());
        entity.setConsentDate(dto.getConsentDate());
        entity.setInsuranceReported(dto.getInsuranceReported());
        entity.setRegistryReported(dto.getRegistryReported());
        entity.setRegistryReportedDate(dto.getRegistryReportedDate());
        entity.setNotes(dto.getNotes());
        if (dto.getActive() != null) {
            entity.setActive(dto.getActive());
        }
    }
}
