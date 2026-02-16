package com.example.hms.service;

import com.example.hms.enums.ConsultationStatus;
import com.example.hms.payload.dto.consultation.ConsultationRequestDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationUpdateDTO;

import java.util.List;
import java.util.UUID;

public interface ConsultationService {

    ConsultationResponseDTO createConsultation(ConsultationRequestDTO request, UUID requestingProviderId);

    ConsultationResponseDTO getConsultation(UUID consultationId);

    List<ConsultationResponseDTO> getConsultationsForPatient(UUID patientId);

    List<ConsultationResponseDTO> getConsultationsForHospital(UUID hospitalId, ConsultationStatus status);

    List<ConsultationResponseDTO> getConsultationsRequestedBy(UUID providerId);

    List<ConsultationResponseDTO> getConsultationsAssignedTo(UUID consultantId, ConsultationStatus status);

    ConsultationResponseDTO acknowledgeConsultation(UUID consultationId, UUID consultantId);

    ConsultationResponseDTO updateConsultation(UUID consultationId, ConsultationUpdateDTO updateDTO);

    ConsultationResponseDTO completeConsultation(UUID consultationId, ConsultationUpdateDTO updateDTO);

    ConsultationResponseDTO cancelConsultation(UUID consultationId, String cancellationReason);

    List<ConsultationResponseDTO> getPendingConsultations(UUID hospitalId);
}
