package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.imaging.ImagingOrderDuplicateMatchDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderResponseDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class ImagingOrderMapper {

    public ImagingOrder toEntity(ImagingOrderRequestDTO dto, Patient patient, Hospital hospital) {
        if (dto == null) {
            return null;
        }

        return ImagingOrder.builder()
            .patient(patient)
            .hospital(hospital)
            .modality(dto.getModality())
            .studyType(dto.getStudyType())
            .bodyRegion(dto.getBodyRegion())
            .laterality(dto.getLaterality())
            .priority(dto.getPriority())
            .status(Boolean.TRUE.equals(dto.getSaveAsDraft()) ? com.example.hms.enums.ImagingOrderStatus.DRAFT : com.example.hms.enums.ImagingOrderStatus.ORDERED)
            .clinicalQuestion(dto.getClinicalQuestion())
            .contrastRequired(dto.getContrastRequired())
            .contrastType(dto.getContrastType())
            .hasContrastAllergy(dto.getHasContrastAllergy())
            .contrastAllergyDetails(dto.getContrastAllergyDetails())
            .sedationRequired(dto.getSedationRequired())
            .sedationType(dto.getSedationType())
            .sedationNotes(dto.getSedationNotes())
            .requiresNpo(dto.getRequiresNpo())
            .hasImplantedDevice(dto.getHasImplantedDevice())
            .implantedDeviceDetails(dto.getImplantedDeviceDetails())
            .requiresPregnancyTest(dto.getRequiresPregnancyTest())
            .needsInterpreter(dto.getNeedsInterpreter())
            .additionalProtocols(dto.getAdditionalProtocols())
            .specialInstructions(dto.getSpecialInstructions())
            .scheduledDate(dto.getScheduledDate())
            .scheduledTime(dto.getScheduledTime())
            .appointmentLocation(dto.getAppointmentLocation())
            .portableStudy(dto.getPortableStudy())
            .requiresAuthorization(dto.getRequiresAuthorization())
            .authorizationNumber(dto.getAuthorizationNumber())
            .orderingProviderName(dto.getOrderingProviderName())
            .orderingProviderNpi(dto.getOrderingProviderNpi())
            .workflowNotes(dto.getWorkflowNotes())
            .encounterId(dto.getEncounterId())
            .build();
    }

    public void updateEntityFromRequest(ImagingOrder order, ImagingOrderRequestDTO dto) {
        if (order == null || dto == null) {
            return;
        }
        order.setModality(dto.getModality());
        order.setStudyType(dto.getStudyType());
        order.setBodyRegion(dto.getBodyRegion());
        order.setLaterality(dto.getLaterality());
        order.setPriority(dto.getPriority());
        order.setClinicalQuestion(dto.getClinicalQuestion());
        order.setContrastRequired(dto.getContrastRequired());
        order.setContrastType(dto.getContrastType());
        order.setHasContrastAllergy(dto.getHasContrastAllergy());
        order.setContrastAllergyDetails(dto.getContrastAllergyDetails());
        order.setSedationRequired(dto.getSedationRequired());
        order.setSedationType(dto.getSedationType());
        order.setSedationNotes(dto.getSedationNotes());
        order.setRequiresNpo(dto.getRequiresNpo());
        order.setHasImplantedDevice(dto.getHasImplantedDevice());
        order.setImplantedDeviceDetails(dto.getImplantedDeviceDetails());
        order.setRequiresPregnancyTest(dto.getRequiresPregnancyTest());
        order.setNeedsInterpreter(dto.getNeedsInterpreter());
        order.setAdditionalProtocols(dto.getAdditionalProtocols());
        order.setSpecialInstructions(dto.getSpecialInstructions());
        order.setScheduledDate(dto.getScheduledDate());
        order.setScheduledTime(dto.getScheduledTime());
        order.setAppointmentLocation(dto.getAppointmentLocation());
        order.setPortableStudy(dto.getPortableStudy());
        order.setRequiresAuthorization(dto.getRequiresAuthorization());
        order.setAuthorizationNumber(dto.getAuthorizationNumber());
        order.setWorkflowNotes(dto.getWorkflowNotes());
        order.setEncounterId(dto.getEncounterId());
    }

    public ImagingOrderResponseDTO toResponseDTO(ImagingOrder order) {
        return toResponseDTO(order, Collections.emptyList());
    }

    public ImagingOrderResponseDTO toResponseDTO(ImagingOrder order, List<ImagingOrder> duplicateMatches) {
        if (order == null) {
            return null;
        }

        return ImagingOrderResponseDTO.builder()
            .id(order.getId())
            .patientId(order.getPatient() != null ? order.getPatient().getId() : null)
            .patientDisplayName(resolvePatientDisplayName(order.getPatient()))
            .patientMrn(resolvePatientMrn(order))
            .hospitalId(order.getHospital() != null ? order.getHospital().getId() : null)
            .hospitalName(order.getHospital() != null ? order.getHospital().getName() : null)
            .modality(order.getModality())
            .studyType(order.getStudyType())
            .bodyRegion(order.getBodyRegion())
            .laterality(order.getLaterality())
            .priority(order.getPriority())
            .status(order.getStatus())
            .clinicalQuestion(order.getClinicalQuestion())
            .contrastRequired(order.getContrastRequired())
            .contrastType(order.getContrastType())
            .hasContrastAllergy(order.getHasContrastAllergy())
            .contrastAllergyDetails(order.getContrastAllergyDetails())
            .sedationRequired(order.getSedationRequired())
            .sedationType(order.getSedationType())
            .sedationNotes(order.getSedationNotes())
            .requiresNpo(order.getRequiresNpo())
            .hasImplantedDevice(order.getHasImplantedDevice())
            .implantedDeviceDetails(order.getImplantedDeviceDetails())
            .requiresPregnancyTest(order.getRequiresPregnancyTest())
            .needsInterpreter(order.getNeedsInterpreter())
            .additionalProtocols(order.getAdditionalProtocols())
            .specialInstructions(order.getSpecialInstructions())
            .scheduledDate(order.getScheduledDate())
            .scheduledTime(order.getScheduledTime())
            .appointmentLocation(order.getAppointmentLocation())
            .portableStudy(order.getPortableStudy())
            .requiresAuthorization(order.getRequiresAuthorization())
            .authorizationNumber(order.getAuthorizationNumber())
            .orderedAt(order.getOrderedAt())
            .orderingProviderName(order.getOrderingProviderName())
            .orderingProviderNpi(order.getOrderingProviderNpi())
            .orderingProviderUserId(order.getOrderingProviderUserId())
            .providerSignedAt(order.getProviderSignedAt())
            .providerSignatureStatement(order.getProviderSignatureStatement())
            .encounterId(order.getEncounterId())
            .duplicateOfRecentOrder(order.getDuplicateOfRecentOrder())
            .duplicateReferenceOrderId(order.getDuplicateReferenceOrderId())
            .duplicateMatches(toDuplicateMatchDTOs(duplicateMatches))
            .cancelledAt(order.getCancelledAt())
            .cancellationReason(order.getCancellationReason())
            .cancelledByName(order.getCancelledByName())
            .workflowNotes(order.getWorkflowNotes())
            .requiresFollowUpCall(order.getRequiresFollowUpCall())
            .updatedAt(order.getUpdatedAt())
            .build();
    }

    private List<ImagingOrderDuplicateMatchDTO> toDuplicateMatchDTOs(List<ImagingOrder> duplicates) {
        if (duplicates == null || duplicates.isEmpty()) {
            return List.of();
        }
        return duplicates.stream()
            .map(this::toDuplicateMatchDTO)
            .toList();
    }

    public ImagingOrderDuplicateMatchDTO toDuplicateMatchDTO(ImagingOrder order) {
        if (order == null) {
            return null;
        }
        return ImagingOrderDuplicateMatchDTO.builder()
            .orderId(order.getId())
            .modality(order.getModality())
            .bodyRegion(order.getBodyRegion())
            .studyType(order.getStudyType())
            .priority(order.getPriority())
            .status(order.getStatus())
            .orderedAt(order.getOrderedAt())
            .scheduledAt(resolveScheduledAt(order))
            .build();
    }

    private LocalDateTime resolveScheduledAt(ImagingOrder order) {
        if (order.getScheduledDate() == null) {
            return null;
        }
        if (order.getScheduledTime() == null || order.getScheduledTime().isBlank()) {
            return order.getScheduledDate().atStartOfDay();
        }
        try {
            LocalTime time = LocalTime.parse(order.getScheduledTime());
            return LocalDateTime.of(order.getScheduledDate(), time);
        } catch (DateTimeParseException ex) {
            return order.getScheduledDate().atStartOfDay();
        }
    }

    private String resolvePatientDisplayName(Patient patient) {
        if (patient == null) {
            return null;
        }
        String first = Optional.ofNullable(patient.getFirstName()).orElse("").trim();
        String last = Optional.ofNullable(patient.getLastName()).orElse("").trim();
        if (!last.isEmpty() && !first.isEmpty()) {
            return last + ", " + first;
        }
        if (!last.isEmpty()) {
            return last;
        }
        return !first.isEmpty() ? first : null;
    }

    private String resolvePatientMrn(ImagingOrder order) {
        if (order == null || order.getPatient() == null) {
            return null;
        }
        UUID hospitalId = order.getHospital() != null ? order.getHospital().getId() : null;
        return order.getPatient().getMrnForHospital(hospitalId);
    }
}
