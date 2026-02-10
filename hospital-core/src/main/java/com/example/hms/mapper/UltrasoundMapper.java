package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.UltrasoundOrder;
import com.example.hms.model.UltrasoundReport;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderRequestDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderResponseDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportRequestDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportResponseDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Mapper for converting between ultrasound entities and DTOs.
 * Handles patient-friendly display information and nested report data.
 */
@Component
public class UltrasoundMapper {

    /**
     * Convert UltrasoundOrder entity to response DTO with patient display info.
     */
    public UltrasoundOrderResponseDTO toOrderResponseDTO(UltrasoundOrder order) {
        if (order == null) {
            return null;
        }

        return UltrasoundOrderResponseDTO.builder()
            .id(order.getId())
            .patientId(order.getPatient() != null ? order.getPatient().getId() : null)
            .patientDisplayName(resolvePatientDisplayName(order.getPatient()))
            .patientMrn(resolvePatientMrn(order.getPatient()))
            .hospitalId(order.getHospital() != null ? order.getHospital().getId() : null)
            .hospitalName(order.getHospital() != null ? order.getHospital().getName() : null)
            .scanType(order.getScanType())
            .status(order.getStatus())
            .orderedDate(order.getOrderedDate())
            .orderedBy(order.getOrderedBy())
            .gestationalAgeAtOrder(order.getGestationalAgeAtOrder())
            .clinicalIndication(order.getClinicalIndication())
            .scheduledDate(order.getScheduledDate())
            .scheduledTime(order.getScheduledTime())
            .appointmentLocation(order.getAppointmentLocation())
            .priority(order.getPriority())
            .isHighRiskPregnancy(order.getIsHighRiskPregnancy())
            .highRiskNotes(order.getHighRiskNotes())
            .specialInstructions(order.getSpecialInstructions())
            .scanCountForPregnancy(order.getScanCountForPregnancy())
            .report(order.getReport() != null ? toReportResponseDTO(order.getReport()) : null)
            .cancelledAt(order.getCancelledAt())
            .cancelledBy(order.getCancelledBy())
            .cancellationReason(order.getCancellationReason())
            .createdAt(order.getCreatedAt())
            .updatedAt(order.getUpdatedAt())
            .build();
    }

    /**
     * Convert list of UltrasoundOrder entities to response DTOs.
     */
    public List<UltrasoundOrderResponseDTO> toOrderResponseDTOList(List<UltrasoundOrder> orders) {
        if (orders == null) {
            return List.of();
        }
        return orders.stream()
            .map(this::toOrderResponseDTO)
            .toList();
    }

    /**
     * Convert UltrasoundOrderRequestDTO to entity (for creation).
     */
    public UltrasoundOrder toOrderEntity(UltrasoundOrderRequestDTO dto, Patient patient, Hospital hospital) {
        if (dto == null) {
            return null;
        }

        return UltrasoundOrder.builder()
            .patient(patient)
            .hospital(hospital)
            .scanType(dto.getScanType())
            .orderedDate(LocalDateTime.now())
            .gestationalAgeAtOrder(dto.getGestationalAgeAtOrder())
            .clinicalIndication(dto.getClinicalIndication())
            .scheduledDate(dto.getScheduledDate())
            .scheduledTime(dto.getScheduledTime())
            .appointmentLocation(dto.getAppointmentLocation())
            .priority(dto.getPriority() != null ? dto.getPriority() : "ROUTINE")
            .isHighRiskPregnancy(Objects.requireNonNullElse(dto.getIsHighRiskPregnancy(), Boolean.FALSE))
            .highRiskNotes(dto.getHighRiskNotes())
            .specialInstructions(dto.getSpecialInstructions())
            .scanCountForPregnancy(dto.getScanCountForPregnancy() != null ? dto.getScanCountForPregnancy() : 1)
            .build();
    }

    /**
     * Update existing UltrasoundOrder entity from request DTO.
     */
    public void updateOrderFromRequest(UltrasoundOrder order, UltrasoundOrderRequestDTO dto) {
        if (order == null || dto == null) {
            return;
        }

        if (dto.getScanType() != null) {
            order.setScanType(dto.getScanType());
        }
        if (dto.getGestationalAgeAtOrder() != null) {
            order.setGestationalAgeAtOrder(dto.getGestationalAgeAtOrder());
        }
        if (dto.getClinicalIndication() != null) {
            order.setClinicalIndication(dto.getClinicalIndication());
        }
        if (dto.getScheduledDate() != null) {
            order.setScheduledDate(dto.getScheduledDate());
        }
        if (dto.getScheduledTime() != null) {
            order.setScheduledTime(dto.getScheduledTime());
        }
        if (dto.getAppointmentLocation() != null) {
            order.setAppointmentLocation(dto.getAppointmentLocation());
        }
        if (dto.getPriority() != null) {
            order.setPriority(dto.getPriority());
        }
        if (dto.getIsHighRiskPregnancy() != null) {
            order.setIsHighRiskPregnancy(dto.getIsHighRiskPregnancy());
        }
        if (dto.getHighRiskNotes() != null) {
            order.setHighRiskNotes(dto.getHighRiskNotes());
        }
        if (dto.getSpecialInstructions() != null) {
            order.setSpecialInstructions(dto.getSpecialInstructions());
        }
    }

    /**
     * Convert UltrasoundReport entity to response DTO.
     */
    public UltrasoundReportResponseDTO toReportResponseDTO(UltrasoundReport report) {
        if (report == null) {
            return null;
        }

        return UltrasoundReportResponseDTO.builder()
            .id(report.getId())
            .ultrasoundOrderId(report.getUltrasoundOrder() != null ? report.getUltrasoundOrder().getId() : null)
            .scanDate(report.getScanDate())
            .scanPerformedBy(report.getScanPerformedBy())
            .scanPerformedByCredentials(report.getScanPerformedByCredentials())
            .gestationalAgeAtScan(report.getGestationalAgeAtScan())
            .gestationalAgeDays(report.getGestationalAgeDays())
            // First trimester measurements
            .nuchalTranslucencyMm(report.getNuchalTranslucencyMm())
            .crownRumpLengthMm(report.getCrownRumpLengthMm())
            .nasalBonePresent(report.getNasalBonePresent())
            // Due date
            .estimatedDueDate(report.getEstimatedDueDate())
            .dueDateConfirmed(report.getDueDateConfirmed())
            // Fetal count and position
            .numberOfFetuses(report.getNumberOfFetuses())
            .fetalPosition(report.getFetalPosition())
            // Second trimester measurements
            .biparietalDiameterMm(report.getBiparietalDiameterMm())
            .headCircumferenceMm(report.getHeadCircumferenceMm())
            .abdominalCircumferenceMm(report.getAbdominalCircumferenceMm())
            .femurLengthMm(report.getFemurLengthMm())
            .estimatedFetalWeightGrams(report.getEstimatedFetalWeightGrams())
            // Placenta and fluid
            .placentalLocation(report.getPlacentalLocation())
            .placentalGrade(report.getPlacentalGrade())
            .amnioticFluidIndex(report.getAmnioticFluidIndex())
            .amnioticFluidLevel(report.getAmnioticFluidLevel())
            // Cervical assessment
            .cervicalLengthMm(report.getCervicalLengthMm())
            // Doppler
            .umbilicalArteryDoppler(report.getUmbilicalArteryDoppler())
            .uterineArteryDoppler(report.getUterineArteryDoppler())
            // Fetal well-being
            .fetalHeartRate(report.getFetalHeartRate())
            .fetalCardiacActivity(report.getFetalCardiacActivity())
            .fetalMovementObserved(report.getFetalMovementObserved())
            .fetalToneNormal(report.getFetalToneNormal())
            // Anatomy
            .anatomySurveyComplete(report.getAnatomySurveyComplete())
            .anatomyFindings(report.getAnatomyFindings())
            // Findings
            .findingCategory(report.getFindingCategory())
            .findingsSummary(report.getFindingsSummary())
            .interpretation(report.getInterpretation())
            .anomaliesDetected(report.getAnomaliesDetected())
            .anomalyDescription(report.getAnomalyDescription())
            // Genetic screening
            .geneticScreeningRecommended(report.getGeneticScreeningRecommended())
            .geneticScreeningType(report.getGeneticScreeningType())
            // Follow-up
            .followUpRequired(report.getFollowUpRequired())
            .followUpRecommendations(report.getFollowUpRecommendations())
            .specialistReferralNeeded(report.getSpecialistReferralNeeded())
            .specialistReferralType(report.getSpecialistReferralType())
            .nextUltrasoundRecommendedWeeks(report.getNextUltrasoundRecommendedWeeks())
            // Report workflow
            .reportFinalizedAt(report.getReportFinalizedAt())
            .reportFinalizedBy(report.getReportFinalizedBy())
            .reportReviewedByProvider(report.getReportReviewedByProvider())
            .providerReviewNotes(report.getProviderReviewNotes())
            .patientNotified(report.getPatientNotified())
            .patientNotifiedAt(report.getPatientNotifiedAt())
            .createdAt(report.getCreatedAt())
            .updatedAt(report.getUpdatedAt())
            .build();
    }

    /**
     * Convert list of UltrasoundReport entities to response DTOs.
     */
    public List<UltrasoundReportResponseDTO> toReportResponseDTOList(List<UltrasoundReport> reports) {
        if (reports == null) {
            return List.of();
        }
        return reports.stream()
            .map(this::toReportResponseDTO)
            .toList();
    }

    /**
     * Convert UltrasoundReportRequestDTO to entity (for creation).
     */
    public UltrasoundReport toReportEntity(UltrasoundReportRequestDTO dto, UltrasoundOrder order, Hospital hospital) {
        if (dto == null) {
            return null;
        }

        UltrasoundReport report = UltrasoundReport.builder()
            .ultrasoundOrder(order)
            .hospital(hospital)
            .scanDate(dto.getScanDate())
            .scanPerformedBy(dto.getScanPerformedBy())
            .scanPerformedByCredentials(dto.getScanPerformedByCredentials())
            .gestationalAgeAtScan(dto.getGestationalAgeAtScan())
            .gestationalAgeDays(dto.getGestationalAgeDays())
            // First trimester
            .nuchalTranslucencyMm(dto.getNuchalTranslucencyMm())
            .crownRumpLengthMm(dto.getCrownRumpLengthMm())
            .nasalBonePresent(dto.getNasalBonePresent())
            // Due date
            .estimatedDueDate(dto.getEstimatedDueDate())
            .dueDateConfirmed(Objects.requireNonNullElse(dto.getDueDateConfirmed(), Boolean.FALSE))
            // Fetal count and position
            .numberOfFetuses(dto.getNumberOfFetuses() != null ? dto.getNumberOfFetuses() : 1)
            .fetalPosition(dto.getFetalPosition())
            // Second trimester
            .biparietalDiameterMm(dto.getBiparietalDiameterMm())
            .headCircumferenceMm(dto.getHeadCircumferenceMm())
            .abdominalCircumferenceMm(dto.getAbdominalCircumferenceMm())
            .femurLengthMm(dto.getFemurLengthMm())
            .estimatedFetalWeightGrams(dto.getEstimatedFetalWeightGrams())
            // Placenta and fluid
            .placentalLocation(dto.getPlacentalLocation())
            .placentalGrade(dto.getPlacentalGrade())
            .amnioticFluidIndex(dto.getAmnioticFluidIndex())
            .amnioticFluidLevel(dto.getAmnioticFluidLevel())
            // Cervical
            .cervicalLengthMm(dto.getCervicalLengthMm())
            // Doppler
            .umbilicalArteryDoppler(dto.getUmbilicalArteryDoppler())
            .uterineArteryDoppler(dto.getUterineArteryDoppler())
            // Fetal well-being
            .fetalHeartRate(dto.getFetalHeartRate())
            .fetalCardiacActivity(Objects.requireNonNullElse(dto.getFetalCardiacActivity(), Boolean.TRUE))
            .fetalMovementObserved(Objects.requireNonNullElse(dto.getFetalMovementObserved(), Boolean.TRUE))
            .fetalToneNormal(Objects.requireNonNullElse(dto.getFetalToneNormal(), Boolean.TRUE))
            // Anatomy
            .anatomySurveyComplete(Objects.requireNonNullElse(dto.getAnatomySurveyComplete(), Boolean.FALSE))
            .anatomyFindings(dto.getAnatomyFindings())
            // Findings
            .findingCategory(dto.getFindingCategory())
            .findingsSummary(dto.getFindingsSummary())
            .interpretation(dto.getInterpretation())
            .anomaliesDetected(Objects.requireNonNullElse(dto.getAnomaliesDetected(), Boolean.FALSE))
            .anomalyDescription(dto.getAnomalyDescription())
            // Genetic screening
            .geneticScreeningRecommended(Objects.requireNonNullElse(dto.getGeneticScreeningRecommended(), Boolean.FALSE))
            .geneticScreeningType(dto.getGeneticScreeningType())
            // Follow-up
            .followUpRequired(Objects.requireNonNullElse(dto.getFollowUpRequired(), Boolean.FALSE))
            .followUpRecommendations(dto.getFollowUpRecommendations())
            .specialistReferralNeeded(Objects.requireNonNullElse(dto.getSpecialistReferralNeeded(), Boolean.FALSE))
            .specialistReferralType(dto.getSpecialistReferralType())
            .nextUltrasoundRecommendedWeeks(dto.getNextUltrasoundRecommendedWeeks())
            .build();

        // Handle report finalization
        if (dto.getReportFinalized() != null && dto.getReportFinalized()) {
            report.setReportFinalizedAt(LocalDateTime.now());
        }

        return report;
    }

    /**
     * Update existing UltrasoundReport entity from request DTO.
     */
    public void updateReportFromRequest(UltrasoundReport report, UltrasoundReportRequestDTO dto) {
        if (report == null || dto == null) {
            return;
        }

        report.setScanDate(dto.getScanDate());
        report.setScanPerformedBy(dto.getScanPerformedBy());
        report.setScanPerformedByCredentials(dto.getScanPerformedByCredentials());
        report.setGestationalAgeAtScan(dto.getGestationalAgeAtScan());
        report.setGestationalAgeDays(dto.getGestationalAgeDays());
        
        // First trimester
        report.setNuchalTranslucencyMm(dto.getNuchalTranslucencyMm());
        report.setCrownRumpLengthMm(dto.getCrownRumpLengthMm());
        report.setNasalBonePresent(dto.getNasalBonePresent());
        
        // Due date
        report.setEstimatedDueDate(dto.getEstimatedDueDate());
        report.setDueDateConfirmed(dto.getDueDateConfirmed());
        
        // Fetal
        report.setNumberOfFetuses(dto.getNumberOfFetuses());
        report.setFetalPosition(dto.getFetalPosition());
        
        // Second trimester
        report.setBiparietalDiameterMm(dto.getBiparietalDiameterMm());
        report.setHeadCircumferenceMm(dto.getHeadCircumferenceMm());
        report.setAbdominalCircumferenceMm(dto.getAbdominalCircumferenceMm());
        report.setFemurLengthMm(dto.getFemurLengthMm());
        report.setEstimatedFetalWeightGrams(dto.getEstimatedFetalWeightGrams());
        
        // Placenta and fluid
        report.setPlacentalLocation(dto.getPlacentalLocation());
        report.setPlacentalGrade(dto.getPlacentalGrade());
        report.setAmnioticFluidIndex(dto.getAmnioticFluidIndex());
        report.setAmnioticFluidLevel(dto.getAmnioticFluidLevel());
        
        // Cervical
        report.setCervicalLengthMm(dto.getCervicalLengthMm());
        
        // Doppler
        report.setUmbilicalArteryDoppler(dto.getUmbilicalArteryDoppler());
        report.setUterineArteryDoppler(dto.getUterineArteryDoppler());
        
        // Fetal well-being
        report.setFetalHeartRate(dto.getFetalHeartRate());
        report.setFetalCardiacActivity(dto.getFetalCardiacActivity());
        report.setFetalMovementObserved(dto.getFetalMovementObserved());
        report.setFetalToneNormal(dto.getFetalToneNormal());
        
        // Anatomy
        report.setAnatomySurveyComplete(dto.getAnatomySurveyComplete());
        report.setAnatomyFindings(dto.getAnatomyFindings());
        
        // Findings
        report.setFindingCategory(dto.getFindingCategory());
        report.setFindingsSummary(dto.getFindingsSummary());
        report.setInterpretation(dto.getInterpretation());
        report.setAnomaliesDetected(dto.getAnomaliesDetected());
        report.setAnomalyDescription(dto.getAnomalyDescription());
        
        // Genetic screening
        report.setGeneticScreeningRecommended(dto.getGeneticScreeningRecommended());
        report.setGeneticScreeningType(dto.getGeneticScreeningType());
        
        // Follow-up
        report.setFollowUpRequired(dto.getFollowUpRequired());
        report.setFollowUpRecommendations(dto.getFollowUpRecommendations());
        report.setSpecialistReferralNeeded(dto.getSpecialistReferralNeeded());
        report.setSpecialistReferralType(dto.getSpecialistReferralType());
        report.setNextUltrasoundRecommendedWeeks(dto.getNextUltrasoundRecommendedWeeks());
        
        // Provider review
        if (dto.getProviderReviewNotes() != null) {
            report.setProviderReviewNotes(dto.getProviderReviewNotes());
        }
        
        // Finalization
        if (dto.getReportFinalized() != null && dto.getReportFinalized() && report.getReportFinalizedAt() == null) {
            report.setReportFinalizedAt(LocalDateTime.now());
        }
    }

    /**
     * Resolve patient display name from Patient entity.
     * Returns "LastName, FirstName" or components available.
     */
    private String resolvePatientDisplayName(Patient patient) {
        if (patient == null) {
            return null;
        }

        String firstName = safeTrim(patient.getFirstName());
        String lastName = safeTrim(patient.getLastName());

        if (lastName != null && firstName != null) {
            return lastName + ", " + firstName;
        }
        if (lastName != null) {
            return lastName;
        }
        if (firstName != null) {
            return firstName;
        }

        return null;
    }

    /**
     * Resolve patient MRN (Medical Record Number).
     * In this system, we use the patient ID as MRN if no specific MRN field exists.
     */
    private String resolvePatientMrn(Patient patient) {
        if (patient == null || patient.getId() == null) {
            return null;
        }
        // TODO: If Patient entity has an mrn field, use that instead
        return patient.getId().toString();
    }

    /**
     * Safely trim a string, returning null if input is null or blank.
     */
    private String safeTrim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
